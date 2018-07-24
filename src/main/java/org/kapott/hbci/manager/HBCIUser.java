
/*  $Id: HBCIUser.java,v 1.2 2011/08/31 14:05:21 willuhn Exp $

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

import org.kapott.hbci.callback.HBCICallback;
import org.kapott.hbci.exceptions.HBCI_Exception;
import org.kapott.hbci.exceptions.ProcessException;
import org.kapott.hbci.passport.HBCIPassportInternal;
import org.kapott.hbci.protocol.Message;
import org.kapott.hbci.status.HBCIMsgStatus;

import java.util.Enumeration;
import java.util.Properties;

/* @brief Instances of this class represent a certain user in combination with
    a certain institute. */
public final class HBCIUser implements IHandlerData {
    private HBCIPassportInternal passport;
    private HBCIKernel kernel;

    /**
     * @brief This constructor initializes a new user instance with the given values
     */
    public HBCIUser(HBCIKernel kernel, HBCIPassportInternal passport) {
        this.kernel = kernel;
        this.passport = passport;
    }

    public void fetchSysId() {
        try {
            passport.getCallback().status(HBCICallback.STATUS_INIT_SYSID, null);
            HBCIUtils.log("fetching new sys-id from institute", HBCIUtils.LOG_INFO);

            // autosecmech
            HBCIUtils.log("checking whether passport is supported (but ignoring result)", HBCIUtils.LOG_DEBUG);
            boolean s = passport.isSupported();
            HBCIUtils.log("passport supported: " + s, HBCIUtils.LOG_DEBUG);

            passport.setSigId(new Long(1));
            passport.setSysId("0");

            HBCIMsgStatus msgStatus;
            boolean restarted = false;
            while (true) {
                msgStatus = doSync("0");

                boolean need_restart = passport.postInitResponseHook(msgStatus);
                if (need_restart) {
                    HBCIUtils.log("for some reason we have to restart this dialog", HBCIUtils.LOG_INFO);
                    if (restarted) {
                        HBCIUtils.log("this dialog already has been restarted once - to avoid endless loops we stop here", HBCIUtils.LOG_WARN);
                        throw new HBCI_Exception("*** restart loop - aborting");
                    }
                    restarted = true;
                } else {
                    break;
                }
            }

            Properties result = msgStatus.getData();

            if (!msgStatus.isOK())
                throw new ProcessException(HBCIUtils.getLocMsg("EXCMSG_SYNCSYSIDFAIL"), msgStatus);

            HBCIInstitute inst = new HBCIInstitute(kernel, passport);
            inst.updateBPD(result);
            updateUPD(result);
            passport.setSysId(result.getProperty("SyncRes.sysid"));

            passport.getCallback().status(HBCICallback.STATUS_INIT_SYSID_DONE, new Object[]{msgStatus, passport.getSysId()});
            HBCIUtils.log("new sys-id is " + passport.getSysId(), HBCIUtils.LOG_DEBUG);
            doDialogEnd(result.getProperty("MsgHead.dialogid"), "2", HBCIKernel.SIGNIT, HBCIKernel.CRYPTIT,
                    HBCIKernel.NEED_CRYPT);
        } catch (Exception e) {
            throw new HBCI_Exception(HBCIUtils.getLocMsg("EXCMSG_SYNCSYSIDFAIL"), e);
        }
    }

    public void fetchSigId() {
        try {
            passport.getCallback().status(HBCICallback.STATUS_INIT_SIGID, null);
            HBCIUtils.log("syncing signature id", HBCIUtils.LOG_INFO);

            // autosecmech
            HBCIUtils.log("checking whether passport is supported (but ignoring result)", HBCIUtils.LOG_DEBUG);
            boolean s = passport.isSupported();
            HBCIUtils.log("passport supported: " + s, HBCIUtils.LOG_DEBUG);

            passport.setSigId(new Long("9999999999999999"));

            HBCIMsgStatus msgStatus;
            boolean restarted = false;
            while (true) {
                msgStatus = doSync("2");

                boolean need_restart = passport.postInitResponseHook(msgStatus);
                if (need_restart) {
                    HBCIUtils.log("for some reason we have to restart this dialog", HBCIUtils.LOG_INFO);
                    if (restarted) {
                        HBCIUtils.log("this dialog already has been restarted once - to avoid endless loops we stop here", HBCIUtils.LOG_WARN);
                        throw new HBCI_Exception("*** restart loop - aborting");
                    }
                    restarted = true;
                } else {
                    break;
                }
            }

            Properties result = msgStatus.getData();

            if (!msgStatus.isOK())
                throw new ProcessException(HBCIUtils.getLocMsg("EXCMSG_SYNCSIGIDFAIL"), msgStatus);

            HBCIInstitute inst = new HBCIInstitute(kernel, passport);
            inst.updateBPD(result);
            updateUPD(result);
            passport.setSigId(new Long(result.getProperty("SyncRes.sigid", "1")));
            passport.incSigId();

            passport.getCallback().status(HBCICallback.STATUS_INIT_SIGID_DONE, new Object[]{msgStatus, passport.getSigId()});
            HBCIUtils.log("signature id set to " + passport.getSigId(), HBCIUtils.LOG_DEBUG);
            doDialogEnd(result.getProperty("MsgHead.dialogid"), "2", HBCIKernel.SIGNIT, HBCIKernel.CRYPTIT,
                    HBCIKernel.NEED_CRYPT);
        } catch (Exception e) {
            throw new HBCI_Exception(HBCIUtils.getLocMsg("EXCMSG_SYNCSIGIDFAIL"), e);
        }
    }

    public void updateUPD(Properties result) {
        HBCIUtils.log("extracting UPD from results", HBCIUtils.LOG_DEBUG);

        Properties p = new Properties();

        for (Enumeration e = result.keys(); e.hasMoreElements(); ) {
            String key = (String) (e.nextElement());
            if (key.startsWith("UPD.")) {
                p.setProperty(key.substring(("UPD.").length()), result.getProperty(key));
            }
        }

        if (p.size() != 0) {
            p.setProperty("_hbciversion", passport.getHBCIVersion());

            // Wir sichern wenigstens noch die TAN-Media-Infos, die vom HBCIHandler vorher abgerufen wurden
            // Das ist etwas unschoen. Sinnvollerweise sollten die SEPA-Infos und TAN-Medien nicht in den
            // UPD gespeichert werden. Dann gehen die auch nicht jedesmal wieder verloren und muessen nicht
            // dauernd neu abgerufen werden. Das wuerde aber einen groesseren Umbau erfordern
            Properties upd = passport.getUPD();
            if (upd != null) {
                String mediaInfo = upd.getProperty("tanmedia.names");
                if (mediaInfo != null) {
                    HBCIUtils.log("rescued TAN media info to new UPD: " + mediaInfo, HBCIUtils.LOG_INFO);
                    p.setProperty("tanmedia.names", mediaInfo);
                }
            }

            String oldVersion = passport.getUPDVersion();
            passport.setUPD(p);

            HBCIUtils.log("installed new UPD [old version: " + oldVersion + ", new version: " + passport.getUPDVersion() + "]", HBCIUtils.LOG_INFO);
            passport.getCallback().status(HBCICallback.STATUS_INIT_UPD_DONE, passport.getUPD());
        }
    }

    public void fetchUPD() {
        try {
            passport.getCallback().status(HBCICallback.STATUS_INIT_UPD, null);
            HBCIUtils.log("fetching UPD (BPD-Version: " + passport.getBPDVersion() + ")", HBCIUtils.LOG_INFO);

            // autosecmech
            HBCIUtils.log("checking whether passport is supported (but ignoring result)", HBCIUtils.LOG_DEBUG);
            boolean s = passport.isSupported();
            HBCIUtils.log("passport supported: " + s, HBCIUtils.LOG_DEBUG);

            HBCIMsgStatus msgStatus;
            boolean restarted = false;
            while (true) {
                msgStatus = doDialogInit();
                boolean need_restart = passport.postInitResponseHook(msgStatus);
                if (need_restart) {
                    HBCIUtils.log("for some reason we have to restart this dialog", HBCIUtils.LOG_INFO);
                    if (restarted) {
                        HBCIUtils.log("this dialog already has been restarted once - to avoid endless loops we stop here", HBCIUtils.LOG_WARN);
                        throw new HBCI_Exception("*** restart loop - aborting");
                    }
                    restarted = true;
                } else {
                    break;
                }
            }

            Properties result = msgStatus.getData();

            if (!msgStatus.isOK())
                throw new ProcessException(HBCIUtils.getLocMsg("EXCMSG_GETUPDFAIL"), msgStatus);

            HBCIInstitute inst = new HBCIInstitute(kernel, passport);
            inst.updateBPD(result);

            updateUPD(result);

            doDialogEnd(result.getProperty("MsgHead.dialogid"), "2", HBCIKernel.SIGNIT, HBCIKernel.CRYPTIT,
                    HBCIKernel.NEED_CRYPT);
        } catch (Exception e) {
            throw new HBCI_Exception(HBCIUtils.getLocMsg("EXCMSG_GETUPDFAIL"), e);
        }
    }

    private HBCIMsgStatus doDialogInit() {
        Message message = MessageFactory.createMessage("DialogInit", passport.getSyntaxDocument());
        message.rawSet("Idn.KIK.blz", passport.getBLZ());
        message.rawSet("Idn.KIK.country", passport.getCountry());
        message.rawSet("Idn.customerid", passport.getCustomerId());
        message.rawSet("Idn.sysid", passport.getSysId());
        message.rawSet("Idn.sysStatus", passport.getSysStatus());
        message.rawSet("ProcPrep.BPD", passport.getBPDVersion());
        message.rawSet("ProcPrep.UPD", "0");
        message.rawSet("ProcPrep.lang", passport.getLang());
        message.rawSet("ProcPrep.prodName", HBCIUtils.getParam("client.product.name", "HBCI4Java"));
        message.rawSet("ProcPrep.prodVersion", HBCIUtils.getParam("client.product.version", "2.5"));
        return kernel.rawDoIt(message, HBCIKernel.SIGNIT, HBCIKernel.CRYPTIT, HBCIKernel.NEED_SIG, HBCIKernel.NEED_CRYPT);
    }

    private void doDialogEnd(String dialogid, String msgnum, boolean signIt, boolean cryptIt, boolean needCrypt) {
        passport.getCallback().status(HBCICallback.STATUS_DIALOG_END, null);

        Message message = MessageFactory.createMessage("DialogEnd", passport.getSyntaxDocument());
        message.rawSet("MsgHead.dialogid", dialogid);
        message.rawSet("MsgHead.msgnum", msgnum);
        message.rawSet("DialogEndS.dialogid", dialogid);
        message.rawSet("MsgTail.msgnum", msgnum);

        HBCIMsgStatus status = kernel.rawDoIt(message, signIt, cryptIt, HBCIKernel.NEED_SIG, needCrypt);

        passport.getCallback().status(HBCICallback.STATUS_DIALOG_END_DONE, status);

        if (!status.isOK()) {
            HBCIUtils.log("dialog end failed: " + status.getErrorString(), HBCIUtils.LOG_ERR);

            String msg = HBCIUtils.getLocMsg("ERR_INST_ENDFAILED");
            if (!HBCIUtils.ignoreError(null, "client.errors.ignoreDialogEndErrors", msg + ": " + status.getErrorString()))
                throw new ProcessException(msg, status);
        }
    }

    private HBCIMsgStatus doSync(String syncMode) {
        Message message = MessageFactory.createMessage("Synch", passport.getSyntaxDocument());
        message.rawSet("Idn.KIK.blz", passport.getBLZ());
        message.rawSet("Idn.KIK.country", passport.getCountry());
        message.rawSet("Idn.customerid", passport.getCustomerId());
        message.rawSet("Idn.sysid", passport.getSysId());
        message.rawSet("Idn.sysStatus", passport.getSysStatus());
        message.rawSet("MsgHead.dialogid", "0");
        message.rawSet("MsgHead.msgnum", "1");
        message.rawSet("MsgTail.msgnum", "1");
        message.rawSet("ProcPrep.BPD", passport.getBPDVersion());
        message.rawSet("ProcPrep.UPD", passport.getUPDVersion());
        message.rawSet("ProcPrep.lang", "0");
        message.rawSet("ProcPrep.prodName", HBCIUtils.getParam("client.product.name", "HBCI4Java"));
        message.rawSet("ProcPrep.prodVersion", HBCIUtils.getParam("client.product.version", "2.5"));
        message.rawSet("Sync.mode", syncMode);
        return kernel.rawDoIt(message, HBCIKernel.SIGNIT, HBCIKernel.CRYPTIT,
                HBCIKernel.NEED_SIG, HBCIKernel.NEED_CRYPT);
    }

    public void updateUserData() {
        if (passport.getSysStatus().equals("1")) {
            if (passport.getSysId().equals("0"))
                fetchSysId();
            if (passport.getSigId().longValue() == -1)
                fetchSigId();
        }

        Properties upd = passport.getUPD();
        Properties bpd = passport.getBPD();
        String hbciVersionOfUPD = upd != null ? upd.getProperty("_hbciversion") : null;

        // Wir haben noch keine BPD. Offensichtlich unterstuetzt die Bank
        // das Abrufen von BPDs ueber einen anonymen Dialog nicht. Also machen
        // wir das jetzt hier mit einem nicht-anonymen Dialog gleich mit
        if (bpd == null || passport.getUPD() == null ||
                hbciVersionOfUPD == null ||
                !hbciVersionOfUPD.equals(passport.getHBCIVersion())) {
            fetchUPD();
        }

        passport.setPersistentData("_registered_user", Boolean.TRUE);

    }

    public HBCIPassportInternal getPassport() {
        return this.passport;
    }
}
