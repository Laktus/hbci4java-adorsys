/*  $Id: HBCIDialog.java,v 1.1 2011/05/04 22:37:46 willuhn Exp $

    This file is part of HBCI4Java
    Copyright (C) 2001-2008  Stefan Palme

    HBCI4Java is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    HBCI4Java is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package org.kapott.hbci.manager;

import lombok.extern.slf4j.Slf4j;
import org.kapott.hbci.GV.AbstractHBCIJob;
import org.kapott.hbci.callback.HBCICallback;
import org.kapott.hbci.exceptions.HBCI_Exception;
import org.kapott.hbci.passport.PinTanPassport;
import org.kapott.hbci.protocol.Message;
import org.kapott.hbci.status.HBCIDialogStatus;
import org.kapott.hbci.status.HBCIExecStatus;
import org.kapott.hbci.status.HBCIInstMessage;
import org.kapott.hbci.status.HBCIMsgStatus;

import java.util.ArrayList;
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
public final class HBCIDialog {

    private String dialogid;  /* The dialogID for this dialog (unique for each dialog) */
    private long msgnum;    /* An automatically managed message counter. */
    private List<List<AbstractHBCIJob>> messages = new ArrayList<>();    /* this array contains all messages to be
    sent (excluding
                                             dialogInit and dialogEnd); each element of the arrayList
                                             is again an ArrayList, where each element is one
                                             task (GV) to be sent with this specific message */
    // liste aller GVs in der aktuellen msg; key ist der hbciCode des jobs, value ist die anzahl dieses jobs in der
    // aktuellen msg
    private HashMap<String, String> listOfGVs = new HashMap<>();
    private PinTanPassport passport;
    private HBCIKernel kernel;

    public HBCIDialog(PinTanPassport passport) {
        this(passport, null, -1);
    }

    public HBCIDialog(PinTanPassport passport, String dialogid, long msgnum) {
        this.dialogid = dialogid;
        this.msgnum = msgnum;
        this.passport = passport;
        this.kernel = new HBCIKernel(passport);
        this.messages.add(new ArrayList<>());

        if (dialogid == null) {
            log.debug("creating new dialog");
            this.fetchBPDAnonymous();
            this.registerUser();
        }
    }

    /**
     * Processing the DialogInit stage and updating institute and user data from the server
     * (mid-level API).
     * <p>
     * This method processes the dialog initialization stage of an HBCIDialog. It creates
     * a new rawMsg in the kernel and processes it. The return values will be
     * passed to appropriate methods in the @c institute and @c user objects to
     * update their internal state with the data received from the institute.
     */
    private HBCIMsgStatus doDialogInit() {
        HBCIMsgStatus msgStatus = new HBCIMsgStatus();

        try {
            log.debug(HBCIUtils.getLocMsg("STATUS_DIALOG_INIT"));
            passport.getCallback().status(HBCICallback.STATUS_DIALOG_INIT, null);

            Message message = MessageFactory.createDialogInit("DialogInit", null, passport);
            msgStatus = kernel.rawDoIt(message, HBCIKernel.SIGNIT, HBCIKernel.CRYPTIT);

            passport.postInitResponseHook(msgStatus);

            HashMap<String, String> result = msgStatus.getData();
            if (msgStatus.isOK()) {
                HBCIInstitute inst = new HBCIInstitute(kernel, passport);
                inst.updateBPD(result);
                inst.extractKeys(result);

                HBCIUser user = new HBCIUser(kernel, passport);
                user.updateUPD(result);

                msgnum = 2;
                dialogid = result.get("MsgHead.dialogid");
                log.debug("dialog-id set to " + dialogid);

                HBCIInstMessage msg;
                for (int i = 0; true; i++) {
                    try {
                        String header = HBCIUtils.withCounter("KIMsg", i);
                        msg = new HBCIInstMessage(result, header);
                    } catch (Exception e) {
                        break;
                    }
                    passport.getCallback().callback(
                        HBCICallback.HAVE_INST_MSG,
                        msg.toString(),
                        HBCICallback.TYPE_NONE,
                        new StringBuffer());
                }
            }

            passport.getCallback().status(HBCICallback.STATUS_DIALOG_INIT_DONE, new Object[]{msgStatus, dialogid});
        } catch (Exception e) {
            msgStatus.addException(e);
        }

        return msgStatus;
    }

    private void fetchBPDAnonymous() {
        try {
            log.debug("registering institute");
            HBCIInstitute inst = new HBCIInstitute(kernel, passport);
            inst.fetchBPDAnonymous();
        } catch (Exception ex) {
            throw new HBCI_Exception(HBCIUtils.getLocMsg("EXCMSG_CANT_REG_INST"), ex);
        }
    }

    private void registerUser() {
        try {
            log.debug("registering user");
            HBCIUser user = new HBCIUser(kernel, passport);
            user.updateUserData();
        } catch (Exception ex) {
            throw new HBCI_Exception(HBCIUtils.getLocMsg("EXCMSG_CANT_REG_USER"), ex);
        }
    }

    private List<HBCIMsgStatus> doJobs() {
        log.info(HBCIUtils.getLocMsg("LOG_PROCESSING_JOBS"));

        ArrayList<HBCIMsgStatus> messageStatusList = new ArrayList<>();

        messages.forEach(tasks -> {
            // loop wird benutzt, um zu zählen, wie oft bereits "nachgehakt" wurde,
            // falls ein bestimmter job nicht mit einem einzigen nachrichtenaustausch
            // abgearbeitet werden konnte (z.b. abholen kontoauszüge)
            int loop = 0;
            HBCIMsgStatus msgstatus = new HBCIMsgStatus();

            // diese schleife loopt solange, bis alle jobs der aktuellen nachricht
            // tatsächlich abgearbeitet wurden (also inclusive "nachhaken")
            while (true) {
                boolean addMsgStatus = true;

                try {
                    int taskNum = 0;

                    Message message = MessageFactory.createMessage("CustomMsg", passport.getSyntaxDocument());

                    // durch alle jobs loopen, die eigentlich in der aktuellen
                    // nachricht abgearbeitet werden müssten
                    for (AbstractHBCIJob task : tasks) {
                        // wenn der Task entweder noch gar nicht ausgeführt wurde
                        // oder in der letzten Antwortnachricht ein entsprechendes
                        // Offset angegeben wurde
                        if (task.needsContinue(loop)) {
                            task.setContinueOffset(loop);

                            log.debug("adding task " + task.getName());
                            passport.getCallback().status(HBCICallback.STATUS_SEND_TASK, task);
                            task.setIdx(taskNum);

                            // Daten für den Task festlegen
                            String header = HBCIUtils.withCounter("GV", taskNum);
                            task.getLowlevelParams().forEach((key, value) ->
                                message.rawSet(header + "." + key, value));

                            taskNum++;
                        }
                    }

                    // wenn keine jobs für die aktuelle message existieren
                    if (taskNum == 0) {
                        log.debug("loop " + (loop + 1) + " aborted, because there are no more tasks to be executed");
                        addMsgStatus = false;
                        break;
                    }

                    message.rawSet("MsgHead.dialogid", dialogid);
                    message.rawSet("MsgHead.msgnum", Long.toString(msgnum));
                    message.rawSet("MsgTail.msgnum", Long.toString(msgnum));
                    nextMsgNum();

                    // nachrichtenaustausch durchführen
                    msgstatus = kernel.rawDoIt(message, HBCIKernel.SIGNIT, HBCIKernel.CRYPTIT);

                    // searching for first segment number that belongs to the custom_msg
                    // we look for entries like {"1","CustomMsg.MsgHead"} and so
                    // on (this data is inserted from the HBCIKernelImpl.rawDoIt() method),
                    // until we find the first segment containing a task
                    int offset;   // this specifies, how many segments precede the first task segment
                    for (offset = 1; true; offset++) {
                        String path = msgstatus.getData().get(Integer.toString(offset));
                        if (path == null || path.startsWith("CustomMsg.GV")) {
                            if (path == null) { // wenn kein entsprechendes Segment gefunden, dann offset auf 0 setzen
                                offset = 0;
                            }
                            break;
                        }
                    }

                    if (offset != 0) {
                        // für jeden Task die entsprechenden Rückgabedaten-Klassen füllen
                        // in fillOutStore wird auch "executed" fuer den jeweiligen Task auf true gesetzt.
                        for (AbstractHBCIJob task : tasks) {
                            if (task.needsContinue(loop)) {
                                // nur wenn der auftrag auch tatsaechlich gesendet werden musste
                                try {
                                    task.fillJobResult(msgstatus, offset);
                                    passport.getCallback().status(HBCICallback.STATUS_SEND_TASK_DONE, task);
                                } catch (Exception e) {
                                    msgstatus.addException(e);
                                }
                            }
                        }
                    }

                    if (msgstatus.hasExceptions()) {
                        log.error("aborting current loop because of errors");
                        break;
                    }

                    loop++;
                } catch (Exception e) {
                    msgstatus.addException(e);
                } finally {
                    if (addMsgStatus) {
                        messageStatusList.add(msgstatus);
                    }
                }
            }
        });

        return messageStatusList;
    }

    /**
     * Processes the DialogEnd stage of an HBCIDialog (mid-level API).
     * <p>
     * Works similarily to doDialogInit().
     */
    private HBCIMsgStatus doDialogEnd() {
        HBCIMsgStatus msgStatus = new HBCIMsgStatus();

        try {
            log.debug(HBCIUtils.getLocMsg("LOG_DIALOG_END"));
            passport.getCallback().status(HBCICallback.STATUS_DIALOG_END, null);

            Message message = MessageFactory.createDialogEnd(passport, dialogid, msgnum);
            nextMsgNum();
            msgStatus = kernel.rawDoIt(message, HBCIKernel.SIGNIT, HBCIKernel.CRYPTIT);

            passport.getCallback().status(HBCICallback.STATUS_DIALOG_END_DONE, msgStatus);
        } catch (Exception e) {
            msgStatus.addException(e);
        }

        return msgStatus;
    }

    /**
     * <p>Ausführen aller bisher erzeugten Aufträge. Diese Methode veranlasst den HBCI-Kernel,
     * die Aufträge, die durch die Aufrufe auszuführen. </p>
     *
     * @return ein Status-Objekt, anhand dessen der Erfolg oder das Fehlschlagen
     * der Dialoge festgestellt werden kann.
     */
    public HBCIExecStatus execute(boolean closeDialog) {
        HBCIExecStatus ret = new HBCIExecStatus();

        log.debug("executing dialog");
        try {
            ret.setDialogStatus(doIt(closeDialog));
        } catch (Exception e) {
            ret.addException(e);
        }
        return ret;
    }

    /**
     * führt einen kompletten dialog mit allen zu diesem
     * dialog gehoerenden nachrichten/tasks aus.
     * <p>
     * bricht diese methode mit einer exception ab, so muessen alle
     * nachrichten bzw. tasks, die noch nicht ausgeführt wurden,
     * von der aufrufenden methode neu erzeugt werden
     */
    private HBCIDialogStatus doIt(boolean closeDialog) {
        log.debug("executing dialog");
        HBCIDialogStatus dialogStatus = new HBCIDialogStatus();

        if (dialogid == null) {
            dialogStatus.setInitStatus(doDialogInit());
        }

        if (dialogid != null || dialogStatus.initStatus.isOK()) {
            dialogStatus.setMsgStatusList(doJobs());

            if (closeDialog) {
                dialogStatus.setEndStatus(doDialogEnd());
            }
        }

        return dialogStatus;
    }

    public String getDialogID() {
        return dialogid;
    }

    public long getMsgnum() {
        return msgnum;
    }

    private void nextMsgNum() {
        msgnum++;
    }

    public void setDialogid(String dialogid) {
        this.dialogid = dialogid;
    }

    private int getTotalNumberOfGVSegsInCurrentMessage() {
        int total = 0;

        for (String hbciCode : listOfGVs.keySet()) {
            total += Integer.parseInt(listOfGVs.get(hbciCode));
        }

        log.debug("there are currently " + total + " GV segs in this message");
        return total;
    }

    public List<AbstractHBCIJob> addTask(AbstractHBCIJob job) {
        return this.addTask(job, true);
    }

    public List<AbstractHBCIJob> addTask(AbstractHBCIJob job, boolean verify) {
        try {
            log.info(HBCIUtils.getLocMsg("EXCMSG_ADDJOB", job.getName()));
            if (verify) {
                job.verifyConstraints();
            }

            // check bpd.numgva here
            String hbciCode = job.getHBCICode();
            if (hbciCode == null) {
                throw new HBCI_Exception(job.getName() + " not supported");
            }

            int gva_counter = listOfGVs.size();
            String counter_st = listOfGVs.get(hbciCode);
            int gv_counter = counter_st != null ? Integer.parseInt(counter_st) : 0;
            int total_counter = getTotalNumberOfGVSegsInCurrentMessage();

            gv_counter++;
            total_counter++;
            if (counter_st == null) {
                gva_counter++;
            }

            // BPD: max. Anzahl GV-Arten
            int maxGVA = passport.getMaxGVperMsg();
            // BPD: max. Anzahl von Job-Segmenten eines bestimmten Typs
            int maxGVSegJob = job.getMaxNumberPerMsg();
            // Passport: evtl. weitere Einschränkungen bzgl. der Max.-Anzahl
            // von Auftragssegmenten pro Nachricht
            int maxGVSegTotal = passport.getMaxGVSegsPerMsg();

            if ((maxGVA > 0 && gva_counter > maxGVA) ||
                (maxGVSegJob > 0 && gv_counter > maxGVSegJob) ||
                (maxGVSegTotal > 0 && total_counter > maxGVSegTotal)) {
                if (maxGVSegTotal > 0 && total_counter > maxGVSegTotal) {
                    log.debug(
                        "have to generate new message because current type of passport only allows " + maxGVSegTotal + " GV segs per message");
                } else {
                    log.debug(
                        "have to generate new message because of BPD restrictions for number of tasks per message; " +
                            "adding job to this new message");
                }
                newMsg();
                gv_counter = 1;
            }

            listOfGVs.put(hbciCode, Integer.toString(gv_counter));

            List<AbstractHBCIJob> messageJobs = messages.get(messages.size() - 1);
            messageJobs.add(job);
            return messageJobs;
        } catch (Exception e) {
            String msg = HBCIUtils.getLocMsg("EXCMSG_CANTADDJOB", job.getName());
            log.error("task " + job.getName() + " will not be executed in current dialog");
            throw new HBCI_Exception(msg, e);
        }
    }

    private void newMsg() {
        log.debug("starting new message");
        messages.add(new ArrayList<>());
        listOfGVs.clear();
    }

    public List<List<AbstractHBCIJob>> getMessages() {
        return this.messages;
    }

    public PinTanPassport getPassport() {
        return passport;
    }

    public HBCIKernel getKernel() {
        return kernel;
    }

    public void setKernel(HBCIKernel kernel) {
        this.kernel = kernel;
    }
}
