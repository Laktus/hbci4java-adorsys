
/*  $Id: Sig.java,v 1.2 2012/03/27 21:33:13 willuhn Exp $

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

package org.kapott.hbci.security;

import org.kapott.hbci.comm.CommPinTan;
import org.kapott.hbci.exceptions.HBCI_Exception;
import org.kapott.hbci.manager.HBCIUtils;
import org.kapott.hbci.passport.HBCIPassportInternal;
import org.kapott.hbci.protocol.*;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;
import java.util.Random;

public final class Sig {
    public final static String SECFUNC_HBCI_SIG_RDH = "1";
    public final static String SECFUNC_HBCI_SIG_DDV = "2";

    public final static String SECFUNC_FINTS_SIG_DIG = "1";
    public final static String SECFUNC_FINTS_SIG_SIG = "2";

    public final static String SECFUNC_SIG_PT_1STEP = "999";
    public final static String SECFUNC_SIG_PT_2STEP_MIN = "900";
    public final static String SECFUNC_SIG_PT_2STEP_MAX = "997";

    public final static String HASHALG_SHA1 = "1";
    public final static String HASHALG_SHA256 = "3";
    public final static String HASHALG_SHA384 = "4";
    public final static String HASHALG_SHA512 = "5";
    public final static String HASHALG_SHA256_SHA256 = "6";
    public final static String HASHALG_RIPEMD160 = "999";

    public final static String SIGALG_DES = "1";
    public final static String SIGALG_RSA = "10";

    public final static String SIGMODE_ISO9796_1 = "16";
    public final static String SIGMODE_ISO9796_2 = "17";
    public final static String SIGMODE_PKCS1 = "18";
    public final static String SIGMODE_PSS = "19";
    public final static String SIGMODE_RETAIL_MAC = "999";

    private Message msg;

    private String u_secfunc;
    private String u_cid;
    private String u_role;
    private String u_range;
    private String u_keyblz;
    private String u_keycountry;
    private String u_keyuserid;
    private String u_keynum;
    private String u_keyversion;
    private String u_sysid;
    private String u_sigid;
    private String u_sigalg;
    private String u_sigmode;
    private String u_hashalg;
    private String sigstring;

    public Sig(Message msg) {
        this.msg = msg;
    }

    // sighead-segment mit werten aus den lokalen variablen füllen
    private void fillSigHead(HBCIPassportInternal passport, SEG sighead) {
        String sigheadName = sighead.getPath();
        String seccheckref = Integer.toString(Math.abs(new Random().nextInt()));

        Date d = new Date();

        sighead.propagateValue(sigheadName + ".secfunc", u_secfunc,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        sighead.propagateValue(sigheadName + ".seccheckref", seccheckref,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        /* TODO: enable this later (when other range types are supported)
             sighead.propagateValue(sigheadName+".range",range,false); */
        sighead.propagateValue(sigheadName + ".role", u_role,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        sighead.propagateValue(sigheadName + ".SecIdnDetails.func", (msg.getName().endsWith("Res") ? "2" : "1"),
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        if (u_cid.length() != 0) {
            // DDV
            sighead.propagateValue(sigheadName + ".SecIdnDetails.cid", "B" + u_cid,
                    SyntaxElement.DONT_TRY_TO_CREATE,
                    SyntaxElement.DONT_ALLOW_OVERWRITE);
        } else {
            // RDH und PinTan
            sighead.propagateValue(sigheadName + ".SecIdnDetails.sysid", u_sysid,
                    SyntaxElement.DONT_TRY_TO_CREATE,
                    SyntaxElement.DONT_ALLOW_OVERWRITE);
        }
        sighead.propagateValue(sigheadName + ".SecTimestamp.date", HBCIUtils.date2StringISO(d),
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        sighead.propagateValue(sigheadName + ".SecTimestamp.time", HBCIUtils.time2StringISO(d),
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);

        sighead.propagateValue(sigheadName + ".secref", u_sigid,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);

        sighead.propagateValue(sigheadName + ".HashAlg.alg", u_hashalg,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        sighead.propagateValue(sigheadName + ".SigAlg.alg", u_sigalg,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        sighead.propagateValue(sigheadName + ".SigAlg.mode", u_sigmode,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);

        sighead.propagateValue(sigheadName + ".KeyName.KIK.country", u_keycountry,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        sighead.propagateValue(sigheadName + ".KeyName.KIK.blz", u_keyblz,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        sighead.propagateValue(sigheadName + ".KeyName.userid", u_keyuserid,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        sighead.propagateValue(sigheadName + ".KeyName.keynum", u_keynum,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        sighead.propagateValue(sigheadName + ".KeyName.keyversion", u_keyversion,
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);

        sighead.propagateValue(sigheadName + ".SecProfile.method", passport.getProfileMethod(),
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
        sighead.propagateValue(sigheadName + ".SecProfile.version", passport.getProfileVersion(),
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
    }

    // sigtail-segment mit werten aus den lokalen variablen füllen
    private void fillSigTail(SEG sighead, SEG sigtail) {
        String sigtailName = sigtail.getPath();

        sigtail.propagateValue(sigtailName + ".seccheckref",
                sighead.getValueOfDE(sighead.getPath() + ".seccheckref"),
                SyntaxElement.DONT_TRY_TO_CREATE,
                SyntaxElement.DONT_ALLOW_OVERWRITE);
    }

    /* daten zusammensammeln, die signiert werden müssen; idx gibt dabei an,
     * die wievielte signatur erzeugt werden soll - wird benötigt, um festzustellen,
     * welche sighead- und sigtail-segmente in die signatur eingehen */
    private String collectHashData(int idx) {
        int numOfPassports = 1;
        StringBuilder ret = new StringBuilder(1024);

        List<MultipleSyntaxElements> msgelementslist = msg.getChildContainers();
        List<SyntaxElement> sigheads = msgelementslist.get(1).getElements();
        List<SyntaxElement> sigtails = msgelementslist.get(msgelementslist.size() - 2).getElements();

        // alle benötigten sighead-segmente zusammensuchen
        for (int i = numOfPassports - 1 - idx; i < (u_range.equals("1") ? (numOfPassports - idx) : numOfPassports); i++) {
            ret.append(((SEG) (sigheads.get(i))).toString());
        }

        // alle nutzdaten hinzufügen
        for (int i = 2; i < msgelementslist.size() - 2; i++) {
            ret.append(msgelementslist.get(i).toString());
        }

        // bei schalen-modell-signaturen alle "inneren" sigtails mit hinzufügen
        for (int i = 0; i < (u_range.equals("1") ? 0 : idx); i++) {
            ret.append(((SEG) (sigtails.get(i))).toString());
        }

        return ret.toString();
    }

    public boolean signIt(HBCIPassportInternal passport) {
        String msgName = msg.getName();
        Node msgNode = msg.getSyntaxDef(msgName, msg.getDocument());
        String dontsignAttr = ((Element) msgNode).getAttribute("dontsign");

        if (dontsignAttr.length() == 0) {
            try {
                int numOfPassports = 1;

                // create an empty sighead and sigtail segment for each required signature
                for (int idx = 0; idx < numOfPassports; idx++) {
                    SEG sighead = new SEG("SigHeadUser", "SigHead", msgName, numOfPassports - 1 - idx, msg.getDocument());
                    SEG sigtail = new SEG("SigTailUser", "SigTail", msgName, idx, msg.getDocument());

                    List<MultipleSyntaxElements> msgelements = msg.getChildContainers();
                    List<SyntaxElement> sigheads = msgelements.get(1).getElements();
                    List<SyntaxElement> sigtails = msgelements.get(msgelements.size() - 2).getElements();

                    // insert sighead segment in msg
                    if ((numOfPassports - 1 - idx) < sigheads.size()) {
                    } else {
                        for (int i = sigheads.size() - 1; i < numOfPassports - 1 - idx; i++) {
                            sigheads.add(null);
                        }
                    }
                    sigheads.set(numOfPassports - 1 - idx, sighead);

                    // insert sigtail segment in message
                    if (idx < sigtails.size()) {
                    } else {
                        for (int i = sigtails.size() - 1; i < idx; i++) {
                            sigtails.add(null);
                        }
                    }
                    sigtails.set(idx, sigtail);
                }

                // fill all sighead and sigtail segments
                for (int idx = 0; idx < numOfPassports; idx++) {
                    setParam("secfunc", passport.getSigFunction());
                    setParam("cid", "");
                    setParam("role", "1");
                    setParam("range", "1");
                    setParam("keyblz", passport.getBLZ());
                    setParam("keycountry", passport.getCountry());
                    setParam("keyuserid", passport.getMySigKeyName());
                    setParam("keynum", passport.getMySigKeyNum());
                    setParam("keyversion", passport.getMySigKeyVersion());
                    setParam("sysid", passport.getSysId());
                    setParam("sigid", passport.getSigId().toString());
                    setParam("sigalg", passport.getSigAlg());
                    setParam("sigmode", passport.getSigMode());
                    setParam("hashalg", passport.getHashAlg());
                    passport.incSigId();

                    List<MultipleSyntaxElements> msgelements = msg.getChildContainers();
                    List<SyntaxElement> sigheads = msgelements.get(1).getElements();
                    List<SyntaxElement> sigtails = msgelements.get(msgelements.size() - 2).getElements();

                    SEG sighead = (SEG) sigheads.get(numOfPassports - 1 - idx);
                    SEG sigtail = (SEG) sigtails.get(idx);

                    fillSigHead(passport, sighead);
                    fillSigTail(sighead, sigtail);
                }

                msg.enumerateSegs(0, SyntaxElement.ALLOW_OVERWRITE);
                msg.validate();
                msg.enumerateSegs(1, SyntaxElement.ALLOW_OVERWRITE);

                // calculate signatures for each segment
                for (int idx = 0; idx < numOfPassports; idx++) {
                    List<MultipleSyntaxElements> msgelements = msg.getChildContainers();
                    List<SyntaxElement> sigtails = msgelements.get(msgelements.size() - 2).getElements();
                    SEG sigtail = (SEG) sigtails.get(idx);

                    /* first calculate hash-result, then sign the hashresult. In
                     * most cases, the hash() step will be executed by the signature
                     * algorithm, so the hash() call returns the message as-is.
                     * Currently the only exception is PKCS#1-10, where an extra
                     * round of hashing must be executed before applying the
                     * signature process */
                    byte[] hashresult = collectHashData(idx).getBytes(CommPinTan.ENCODING);
                    byte[] signature = passport.sign(hashresult);

                    if (passport.needUserSig()) {
                        String pintan = new String(signature, CommPinTan.ENCODING);
                        int pos = pintan.indexOf("|");

                        if (pos != -1) {
                            // wenn überhaupt eine signatur existiert
                            // (wird für server benötigt)
                            String pin = pintan.substring(0, pos);
                            msg.propagateValue(sigtail.getPath() + ".UserSig.pin", pin,
                                    SyntaxElement.DONT_TRY_TO_CREATE,
                                    SyntaxElement.DONT_ALLOW_OVERWRITE);

                            if (pos < pintan.length() - 1) {
                                String tan = pintan.substring(pos + 1);
                                msg.propagateValue(sigtail.getPath() + ".UserSig.tan", tan,
                                        SyntaxElement.DONT_TRY_TO_CREATE,
                                        SyntaxElement.DONT_ALLOW_OVERWRITE);
                            }
                        }
                    } else { // normale signatur
                        msg.propagateValue(sigtail.getPath() + ".sig", "B" + new String(signature, CommPinTan.ENCODING),
                                SyntaxElement.DONT_TRY_TO_CREATE,
                                SyntaxElement.DONT_ALLOW_OVERWRITE);
                    }

                    msg.validate();
                    msg.enumerateSegs(1, SyntaxElement.ALLOW_OVERWRITE);
                    msg.autoSetMsgSize();
                }
            } catch (Exception ex) {
                throw new HBCI_Exception("*** error while signing", ex);
            }
        } else HBCIUtils.log("did not sign - message does not want to be signed", HBCIUtils.LOG_DEBUG);

        return true;
    }

    private void readSigHead(HBCIPassportInternal passport) {
        String sigheadName = msg.getName() + ".SigHead";

        u_secfunc = msg.getValueOfDE(sigheadName + ".secfunc");

        // TODO: das ist abgeschaltet, weil das Thema "Sicherheitsfunktion, kodiert"
        // ab FinTS-3 anders behandelt wird - siehe Spez.
        /*
        if (u_secfunc.equals("2")) {
            // DDV
            u_cid=msg.getValueOfDE(sigheadName+".SecIdnDetails.cid");
            if (!u_cid.equals(mainPassport.getCID())) {
                String errmsg=HBCIUtils.getLocMsg("EXCMSG_CRYPTCIDFAIL");
                if (!HBCIUtils.ignoreError(null,"client.errors.ignoreSignErrors",errmsg))
                    throw new HBCI_Exception(errmsg);
            }
        } else {
            // RDH und PinTan (= 2 und 999)
            try {
                // falls noch keine system-id ausgehandelt wurde, so sendet der
                // hbci-server auch keine... deshalb der try-catch-block
                u_sysid=msg.getValueOfDE(sigheadName+".SecIdnDetails.sysid");
            } catch (Exception e) {
                u_sysid="0";
            }
        }
        */

        u_role = msg.getValueOfDE(sigheadName + ".role");
        u_range = msg.getValueOfDE(sigheadName + ".range");
        u_keycountry = msg.getValueOfDE(sigheadName + ".KeyName.KIK.country");
        u_keyuserid = msg.getValueOfDE(sigheadName + ".KeyName.userid");
        u_keynum = msg.getValueOfDE(sigheadName + ".KeyName.keynum");
        u_keyversion = msg.getValueOfDE(sigheadName + ".KeyName.keyversion");
        u_sigid = msg.getValueOfDE(sigheadName + ".secref");
        u_sigalg = msg.getValueOfDE(sigheadName + ".SigAlg.alg");
        u_sigmode = msg.getValueOfDE(sigheadName + ".SigAlg.mode");
        u_hashalg = msg.getValueOfDE(sigheadName + ".HashAlg.alg");

        // Die Angabe der BLZ ist nicht unbedingt verpflichtend (für 280 aber schon...). Trotzdem gibt es wohl
        // Banken die das nicht interessiert...
        try {
            u_keyblz = msg.getValueOfDE(sigheadName + ".KeyName.KIK.blz");
        } catch (Exception e) {
            HBCIUtils.log("missing bank code in message signature, ignoring...", HBCIUtils.LOG_WARN);
        }

        if (passport.needUserSig()) {
            // TODO: bei anderen user-signaturen hier allgemeineren code schreiben
            Hashtable<String, String> values = new Hashtable<>();
            msg.extractValues(values);

            String pin = values.get(msg.getName() + ".SigTail.UserSig.pin");
            String tan = values.get(msg.getName() + ".SigTail.UserSig.tan");

            sigstring = ((pin != null) ? pin : "") + "|" + ((tan != null) ? tan : "");
        } else {
            sigstring = msg.getValueOfDE(msg.getName() + ".SigTail.sig");
        }

        String checkref = msg.getValueOfDE(msg.getName() + ".SigHead.seccheckref");
        String checkref2 = msg.getValueOfDE(msg.getName() + ".SigTail.seccheckref");

        if (checkref == null || !checkref.equals(checkref2)) {
            String errmsg = HBCIUtils.getLocMsg("EXCMSG_SIGREFFAIL");
            if (!HBCIUtils.ignoreError(null, "client.errors.ignoreSignErrors", errmsg))
                throw new HBCI_Exception(errmsg);
        }

        // TODO: dieser test ist erst mal deaktiviert. grund: beim pin/tan-zwei-
        // schritt-verfahren ist die passport.getSigFunction()==922 (z.B.). 
        // wenn jedoch zeitgleich HITAN über eine bankensignatur abgesichert
        // wird, steht in der antwort secfunc=1 (RDH) drin. 
        /*
        if (!u_secfunc.equals(mainPassport.getSigFunction())) {
            String errmsg=HBCIUtils.getLocMsg("EXCMSG_SIGTYPEFAIL",new String[] {u_secfunc,mainPassport.getSigFunction()});
            if (!HBCIUtils.ignoreError(null,"client.errors.ignoreSignErrors",errmsg))
                throw new HBCI_Exception(errmsg);
        }
        */

        // TODO: hier auch die DEG SecProfile lesen und überprüfen

        // TODO: diese checks werden vorerst abgeschaltet, damit die pin-tan sigs
        // ohne probleme funktionieren
        /*
        if (!u_sigalg.equals(passport.getSigAlg()))
            throw new HBCI_Exception(HBCIUtils.getLocMsg("EXCMSG_SIGALGFAIL",new String[] {u_sigalg,passport.getSigAlg()}));
        if (!u_sigmode.equals(passport.getSigMode()))
            throw new HBCI_Exception(HBCIUtils.getLocMsg("EXCMSG_SIGMODEFAIL",new String[] {u_sigmode,passport.getSigMode()}));
        if (!u_hashalg.equals(passport.getHashAlg()))
            throw new HBCI_Exception(HBCIUtils.getLocMsg("EXCMSG_SIGHASHFAIL",new String[] {u_hashalg,passport.getHashAlg()}));
        */
    }

    private boolean hasSig() {
        boolean ret = true;
        MultipleSyntaxElements seglist = (msg.getChildContainers().get(1));

        if (seglist instanceof MultipleSEGs) {
            SEG sighead = null;
            try {
                /* TODO: multiple signatures not supported until now */
                sighead = (SEG) (seglist.getElements().get(0));
            } catch (IndexOutOfBoundsException e) {
                ret = false;
            }

            if (ret) {
                String sigheadCode = "HNSHK";

                if (!sighead.getCode().equals(sigheadCode))
                    ret = false;
            }
        } else ret = false;

        return ret;
    }

    public boolean verify(HBCIPassportInternal passport) {
        if (passport.hasInstSigKey()) {
            String msgName = msg.getName();
            Node msgNode = msg.getSyntaxDef(msgName, passport.getSyntaxDocument());
            String dontsignAttr = ((Element) msgNode).getAttribute("dontsign");

            if (dontsignAttr.length() == 0) {
                if (hasSig()) {
                    readSigHead(passport);
                    return true;
                } else {
                    HBCIUtils.log("message has no signature", HBCIUtils.LOG_WARN);
                    /* das ist nur für den fall, dass das institut prinzipiell nicht signiert
                       (also für den client-code);
                       die verify()-funktion für den server-code überprüft selbstständig, ob
                       tatsächlich eine benötigte signatur vorhanden ist (verlässt sich also nicht
                       auf dieses TRUE, was beim fehlen einer signatur zurückgegeben wird */
                    return true;
                }
            } else {
                HBCIUtils.log("message does not need a signature", HBCIUtils.LOG_DEBUG);
                return true;
            }
        } else {
            HBCIUtils.log("can not check signature - no signature key available", HBCIUtils.LOG_WARN);
            return true;
        }
    }

    public void setParam(String key, String value) {
        try {
            Field f = this.getClass().getDeclaredField("u_" + key);
            HBCIUtils.log("setting " + key + " to " + value, HBCIUtils.LOG_DEBUG);
            f.set(this, value);
        } catch (Exception ex) {
            throw new HBCI_Exception("*** error while setting sig parameter", ex);
        }
    }
}
