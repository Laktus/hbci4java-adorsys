/*
 * Copyright 2018-2019 adorsys GmbH & Co KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kapott.hbci.dialog;

import lombok.extern.slf4j.Slf4j;
import org.kapott.hbci.GV.AbstractHBCIJob;
import org.kapott.hbci.GV.GVTAN2Step;
import org.kapott.hbci.callback.HBCICallback;
import org.kapott.hbci.manager.*;
import org.kapott.hbci.passport.PinTanPassport;
import org.kapott.hbci.protocol.Message;
import org.kapott.hbci.status.HBCIExecStatus;
import org.kapott.hbci.status.HBCIInstMessage;
import org.kapott.hbci.status.HBCIMsgStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/* @brief A class for managing exactly one HBCI-Dialog

    A HBCI-Dialog consists of a number of HBCI-messages. These
    messages will be sent (and the responses received) one
    after the other, without timegaps between them (to avoid
    network timeout problems).

    The messages generated by a HBCI-Dialog are at first DialogInit-Message,
    after that a message that contains one ore more "Geschaeftsvorfaelle"
    (i.e. the stuff that you really want to do via HBCI), and at last
    a DialogEnd-Message.

    In this class we have two API-levels, a mid-level API (for manually
    creating and processing dialogs) and a high-level API (for automatic
    creation of typical HBCI-dialogs). For each method the API-level is
    given in its description
*/
@Slf4j
public final class HBCIJobsDialog extends AbstractHbciDialog {

    private long msgnum;    /* An automatically managed message counter. */

    public HBCIJobsDialog(PinTanPassport passport) {
        this(passport, null, -1);
    }

    public HBCIJobsDialog(PinTanPassport passport, String dialogId, long msgnum) {
        super(passport);
        this.dialogId = dialogId;
        this.msgnum = msgnum;
    }

    @Override
    public HBCIMsgStatus dialogInit() {
        log.debug("start dialog");
        HBCIMsgStatus msgStatus = new HBCIMsgStatus();

        try {
            log.debug(HBCIUtils.getLocMsg("STATUS_DIALOG_INIT"));
            passport.getCallback().status(HBCICallback.STATUS_DIALOG_INIT, null);

            Message message = MessageFactory.createDialogInit("DialogInit", null, passport);
            msgStatus = kernel.rawDoIt(message, HBCIKernel.SIGNIT, HBCIKernel.CRYPTIT);

            passport.postInitResponseHook(msgStatus);

            HashMap<String, String> result = msgStatus.getData();
            if (msgStatus.isOK()) {
                msgnum = 2;
                this.dialogId = result.get("MsgHead.dialogid");
                handleBankMessages(result);
            }

            passport.getCallback().status(HBCICallback.STATUS_DIALOG_INIT_DONE, new Object[]{msgStatus, dialogId});
        } catch (Exception e) {
            msgStatus.addException(e);
        }

        return msgStatus;
    }

    private void handleBankMessages(HashMap<String, String> result) {
        HBCIInstMessage bankMessage;
        for (int i = 0; true; i++) {
            try {
                String header = HBCIUtils.withCounter("KIMsg", i);
                bankMessage = new HBCIInstMessage(result, header);
            } catch (Exception e) {
                break;
            }
            passport.getCallback().callback(
                HBCICallback.HAVE_INST_MSG,
                Collections.singletonList(bankMessage.toString()),
                HBCICallback.TYPE_NONE,
                new StringBuilder());
        }
    }

    private List<HBCIMsgStatus> doJobs() {
        log.info(HBCIUtils.getLocMsg("LOG_PROCESSING_JOBS"));

        ArrayList<HBCIMsgStatus> messageStatusList = new ArrayList<>();

        while (true) {
            HBCIMessage msg = this.queue.poll();

            if (msg == null) {
                break;
            }
            patchMessageForSca(msg);

            HBCIMsgStatus msgstatus = new HBCIMsgStatus();

            boolean addMsgStatus = true;

            try {
                int taskNum = 0;

                Message message = MessageFactory.createMessage("CustomMsg", passport.getSyntaxDocument());

                // durch alle jobs loopen, die eigentlich in der aktuellen
                // nachricht abgearbeitet werden müssten
                for (AbstractHBCIJob task : msg.getTasks()) {
                    if (task.skipped())
                        continue;

                    // Uebernimmt den aktuellen loop-Wert in die Lowlevel-Parameter
                    task.applyOffset();
                    task.setIdx(taskNum);

                    // Daten für den Task festlegen
                    String header = HBCIUtils.withCounter("GV", taskNum);
                    task.getLowlevelParams().forEach((key, value) ->
                        message.rawSet(header + "." + key, value));

                    taskNum++;
                }

                // Das passiert immer dann, wenn wir in der Message nur ein HKTAN#2 aus Prozess-Variante 2 hatten.
                // Dieses aufgrund einer 3076-SCA-Ausnahme aber nicht benoetigt wird.
                if (taskNum == 0) {
                    addMsgStatus = false;
                    break;
                }

                message.rawSet("MsgHead.dialogid", dialogId);
                message.rawSet("MsgHead.msgnum", Long.toString(msgnum));
                message.rawSet("MsgTail.msgnum", Long.toString(msgnum));

                // nachrichtenaustausch durchführen
                msgstatus = kernel.rawDoIt(message, HBCIKernel.SIGNIT, HBCIKernel.CRYPTIT);
                nextMsgNum();

                final int segnum = msgstatus.findTaskSegment();
                if (segnum != 0) {
                    // für jeden Task die entsprechenden Rückgabedaten-Klassen füllen
                    for (AbstractHBCIJob task : msg.getTasks()) {
                        if (task.skipped())
                            continue;

                        try {
                            task.fillJobResult(msgstatus, segnum);
                        } catch (Exception e) {
                            msgstatus.addException(e);
                        }
                    }
                }

                if (msgstatus.hasExceptions()) {
                    log.error("aborting current loop because of errors");
                    break;
                }

                ////////////////////////////////////////////////////////////////////
                // Jobs erneut ausfuehren, falls noetig.
                HBCIMessage newMsg = null;
                for (AbstractHBCIJob task : msg.getTasks()) {
                    if (task.skipped())
                        continue;

                    AbstractHBCIJob redo = task.redo();
                    if (redo != null) {
                        // Nachricht bei Bedarf erstellen und an die Queue haengen
                        if (newMsg == null) {
                            newMsg = new HBCIMessage();
                            queue.append(newMsg);
                        }

                        // Task hinzufuegen
                        log.debug("repeat task " + redo.getName());
                        newMsg.append(redo);
                    }
                }
                //
                ////////////////////////////////////////////////////////////////////
            } catch (Exception e) {
                msgstatus.addException(e);
            } finally {
                if (addMsgStatus) {
                    messageStatusList.add(msgstatus);
                }
            }
        }

        return messageStatusList;
    }

    private void patchMessageForSca(HBCIMessage msg) {
        AbstractHBCIJob tan2StepRequiredJob = msg.getTasks().stream()
            .filter(AbstractHBCIJob::isNeedsTan)
            .findAny()
            .orElse(null);

        if (tan2StepRequiredJob != null && msg.findTask("HKTAN") == null) {
            final GVTAN2Step hktan = new GVTAN2Step(getPassport(), tan2StepRequiredJob);
            hktan.setProcess(KnownTANProcess.PROCESS2_STEP1);
            hktan.setSegVersion(getPassport().getCurrentSecMechInfo().getSegversion()); // muessen wir explizit
            // setzen, damit wir das HKTAN in der gleichen Version schicken, in der das HITANS kam.

            if (getPassport().tanMediaNeeded()) {
                hktan.setParam("tanmedia", getPassport().getCurrentSecMechInfo().getMedium());
            }

            msg.append(hktan);
        }
    }

    /**
     * <p>Ausführen aller bisher erzeugten Aufträge. Diese Methode veranlasst den HBCI-Kernel,
     * die Aufträge, die durch die Aufrufe auszuführen. </p>
     *
     * @return ein Status-Objekt, anhand dessen der Erfolg oder das Fehlschlagen
     * der Dialoge festgestellt werden kann.
     */
    @Override
    public HBCIExecStatus execute() {
        HBCIExecStatus ret = new HBCIExecStatus();

        log.debug("executing dialog");
        try {
            ret.setMsgStatusList(doJobs());
        } catch (Exception e) {
            ret.addException(e);
        }
        return ret;
    }

    public long getMsgnum() {
        return msgnum;
    }

    @Override
    public boolean isAnonymous() {
        return false;
    }

    private void nextMsgNum() {
        msgnum++;
    }

}
