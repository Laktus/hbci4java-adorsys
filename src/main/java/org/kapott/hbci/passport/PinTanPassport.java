/*  $Id: AbstractPinTanPassport.java,v 1.6 2011/06/06 10:30:31 willuhn Exp $

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

package org.kapott.hbci.passport;

import lombok.extern.slf4j.Slf4j;
import org.kapott.cryptalgs.CryptAlgs4JavaProvider;
import org.kapott.hbci.GV.AbstractHBCIJob;
import org.kapott.hbci.GV_Result.GVRTANMediaList;
import org.kapott.hbci.callback.HBCICallback;
import org.kapott.hbci.exceptions.HBCI_Exception;
import org.kapott.hbci.manager.HBCIKey;
import org.kapott.hbci.manager.HBCIProduct;
import org.kapott.hbci.manager.HBCITwoStepMechanism;
import org.kapott.hbci.security.Crypt;
import org.kapott.hbci.security.Sig;
import org.kapott.hbci.status.HBCIMsgStatus;
import org.kapott.hbci.status.HBCIRetVal;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.*;

import static org.kapott.hbci.security.Sig.SECFUNC_SIG_PT_1STEP;

@Slf4j
public class PinTanPassport extends AbstractHBCIPassport {

    public static final String BPD_KEY_LASTUPDATE = "_lastupdate";
    public static final String BPD_KEY_HBCIVERSION = "_hbciversion";

    static {
        Security.addProvider(new CryptAlgs4JavaProvider());
    }

    private String proxy;
    private String proxyuser;
    private String proxypass;
    private Map<String, HBCITwoStepMechanism> bankTwostepMechanisms = new HashMap<>();
    private HBCITwoStepMechanism currentSecMechInfo;
    private List<String> userTwostepMechanisms = new ArrayList<>();
    private String pin;

    public PinTanPassport(String hbciversion, Map<String, String> properties, HBCICallback callback,
                          HBCIProduct product) {
        super(hbciversion, properties, callback, product);
    }

    @Override
    public byte[][] encrypt(byte[] bytes) {
        try {
            int padLength = bytes[bytes.length - 1];
            byte[] encrypted = new String(bytes, 0, bytes.length
                - padLength, StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.ISO_8859_1);
            return new byte[][]{new byte[8], encrypted};
        } catch (Exception ex) {
            throw new HBCI_Exception("*** encrypting message failed", ex);
        }
    }

    @Override
    public byte[] decrypt(byte[] bytes, byte[] bytes1) {
        try {
            return (new String(bytes1, StandardCharsets.ISO_8859_1) + '\001')
                .getBytes(StandardCharsets.ISO_8859_1);
        } catch (Exception ex) {
            throw new HBCI_Exception("*** decrypting of message failed", ex);
        }
    }

    /**
     * gets the BPD out of the result and store it in the
     * passport field
     */
    public void updateBPD(Map<String, String> result) {
        log.debug("extracting BPD from results");
        HashMap<String, String> newBPD = new HashMap<>();

        result.keySet().forEach(key -> {
            if (key.startsWith("BPD.")) {
                newBPD.put(key.substring(("BPD.").length()), result.get(key));
            }
        });

        if (newBPD.size() != 0) {
            newBPD.put(BPD_KEY_HBCIVERSION, getHBCIVersion());
            newBPD.put(BPD_KEY_LASTUPDATE, String.valueOf(System.currentTimeMillis()));
            setBPD(newBPD);
            log.info("installed new BPD with version " + getBPDVersion());
            getCallback().status(HBCICallback.STATUS_INST_BPD_INIT_DONE, getBPD());
        }
    }

    @Override
    public void setBPD(Map<String, String> newBPD) {
        super.setBPD(newBPD);

        if (newBPD != null && newBPD.size() != 0) {
            // hier die liste der verfügbaren sicherheitsverfahren aus den
            // bpd (HITANS) extrahieren

            bankTwostepMechanisms.clear();

            // willuhn 2011-06-06 Maximal zulaessige HITANS-Segment-Version ermitteln
            // Hintergrund: Es gibt User, die nur HHD 1.3-taugliche TAN-Generatoren haben,
            // deren Banken aber auch HHD 1.4 beherrschen. In dem Fall wuerde die Bank
            // HITANS/HKTAN/HITAN in Segment-Version 5 machen, was in der Regel dazu fuehren
            // wird, dass HHD 1.4 zur Anwendung kommt. Das bewirkt, dass ein Flicker-Code
            // erzeugt wird, der vom TAN-Generator des Users gar nicht lesbar ist, da dieser
            // kein HHD 1.4 beherrscht. Mit dem folgenden Parameter kann die Maximal-Version
            // des HITANS-Segments nach oben begrenzt werden, so dass z.Bsp. HITANS5 ausgefiltert
            // wird.
            int maxAllowedVersion = 0;

            for (Map.Entry<String, String> bpdEntry : newBPD.entrySet()) {
                String key = bpdEntry.getKey();
                // newBPD.getProperty("Params_x.TAN2StepParY.ParTAN2StepZ.TAN2StepParamsX_z.*")
                if (key.startsWith("Params")) {
                    String subkey = key.substring(key.indexOf('.') + 1);
                    if (subkey.startsWith("TAN2StepPar")) {

                        // willuhn 2011-05-13 Wir brauchen die Segment-Version, weil mittlerweile TAN-Verfahren
                        // mit identischer Sicherheitsfunktion in unterschiedlichen Segment-Versionen auftreten koennen
                        // Wenn welche mehrfach vorhanden sind, nehmen wir nur das aus der neueren Version
                        int segVersion = Integer.parseInt(subkey.substring(11, 12));

                        subkey = subkey.substring(subkey.indexOf('.') + 1);
                        if (subkey.startsWith("ParTAN2Step") && subkey.endsWith(".secfunc")) {
                            // willuhn 2011-06-06 Segment-Versionen ueberspringen, die groesser als die max.
                            // zulaessige sind
                            if (maxAllowedVersion > 0 && segVersion > maxAllowedVersion) {
                                log.info("skipping segversion " + segVersion + ", larger than allowed version " + maxAllowedVersion);
                                continue;
                            }

                            String secfunc = bpdEntry.getValue();

                            // willuhn 2011-05-13 Checken, ob wir das Verfahren schon aus einer aktuelleren
                            // Segment-Version haben
                            HBCITwoStepMechanism prev = bankTwostepMechanisms.get(secfunc);
                            if (prev != null) {
                                // Wir haben es schonmal. Mal sehen, welche Versionsnummer es hat
                                if (prev.getSegversion() > segVersion) {
                                    log.debug("found another twostepmech " + secfunc + " in segversion " + segVersion + ", allready have one in segversion " + prev.getSegversion() + ", ignoring segversion " + segVersion);
                                    continue;
                                }
                            }

                            HBCITwoStepMechanism entry = new HBCITwoStepMechanism();

                            // willuhn 2011-05-13 Wir merken uns die Segment-Version in dem Zweischritt-Verfahren
                            // Daran koennen wir erkennen, ob wir ein mehrfach auftretendes
                            // Verfahren ueberschreiben koennen oder nicht.
                            entry.setSegversion(segVersion);

                            String paramHeader = key.substring(0, key.lastIndexOf('.'));
                            // Params_x.TAN2StepParY.ParTAN2StepZ.TAN2StepParamsX_z

                            // alle properties durchlaufen und alle suchen, die mit dem
                            // paramheader beginnen, und die entsprechenden werte im
                            // entry abspeichern
                            for (Map.Entry<String, String> newBPDEntry : newBPD.entrySet()) {
                                String key2 = newBPDEntry.getKey();
                                if (key2.startsWith(paramHeader + ".")) {
                                    int dotPos = key2.lastIndexOf('.');
                                    entry.setValue(key2.substring(dotPos + 1), newBPDEntry.getValue());
                                }
                            }

                            // diesen mechanismus abspeichern
                            bankTwostepMechanisms.put(secfunc, entry);
                        }
                    }
                }
            }
        }
    }

    private void searchFor3920s(List<HBCIRetVal> rets) {
        for (HBCIRetVal ret : rets) {
            if (ret.code.equals("3920")) {
                this.userTwostepMechanisms.clear();

                int l2 = ret.params.length;
                this.userTwostepMechanisms.addAll(Arrays.asList(ret.params).subList(0, l2));

                if (userTwostepMechanisms.size() > 0 && currentSecMechInfo == null) {
                    setCurrentSecMechInfo(bankTwostepMechanisms.get(userTwostepMechanisms.get(0)));
                    log.info("using secfunc: {}", currentSecMechInfo);
                }
            }
            log.debug("autosecfunc: found 3920 in response - updated list of allowed twostepmechs with " + userTwostepMechanisms.size() + " entries");
        }
    }

    private void searchFor3072s(List<HBCIRetVal> rets) {
        for (HBCIRetVal ret : rets) {
            if (ret.code.equals("3072")) {
                String newCustomerId = "";
                String newUserId = "";
                int l2 = ret.params.length;
                if (l2 > 0) {
                    newUserId = ret.params[0];
                    newCustomerId = ret.params[0];
                }
                if (l2 > 1) {
                    newCustomerId = ret.params[1];
                }
                if (l2 > 0) {
                    log.debug("autosecfunc: found 3072 in response - change user id");
                    // Aufrufer informieren, dass UserID und CustomerID geändert wurde
                    StringBuilder retData = new StringBuilder();
                    retData.append(newUserId)
                        .append("|")
                        .append(newCustomerId);
                    callback.callback(HBCICallback.USERID_CHANGED, Collections.singletonList("*** User ID changed"),
                        HBCICallback.TYPE_TEXT,
                        retData);
                }
            }
        }
    }

    public void updateUPD(Map<String, String> result) {
        log.debug("extracting UPD from results");

        Map<String, String> newUpd = new HashMap<>();

        result.forEach((key, value) -> {
            if (key.startsWith("UPD.")) {
                newUpd.put(key.substring(4), value);
            }
        });

        if (newUpd.size() != 0) {
            newUpd.put("_hbciversion", getHBCIVersion());

            String oldVersion = getUPDVersion();
            setUPD(newUpd);

            log.info("installed new UPD [old version: " + oldVersion + ", new version: " + getUPDVersion() + "]");
            getCallback().status(HBCICallback.STATUS_INIT_UPD_DONE, getUPD());
        }
    }

    public void postInitResponseHook(HBCIMsgStatus msgStatus) {
        if (!msgStatus.isOK()) {
            log.debug("dialog init ended with errors - searching for return code 'wrong PIN'");

            Optional.ofNullable(msgStatus.getInvalidPinCode())
                .ifPresent(invalidPinCode -> {
                    log.info("detected 'invalid PIN' error - clearing passport PIN");
                    clearPIN();

                    // Aufrufer informieren, dass falsche PIN eingegeben wurde (um evtl. PIN aus Puffer zu löschen,
                    // etc.)
                    StringBuilder retData = new StringBuilder();
                    callback.callback(HBCICallback.WRONG_PIN, msgStatus.getErrorList(), HBCICallback.TYPE_TEXT,
                        retData);
                });

        }

        log.debug("autosecfunc: search for 3920s in response to detect allowed twostep secmechs");

        searchFor3920s(msgStatus.globStatus.getWarnings());
        searchFor3920s(msgStatus.segStatus.getWarnings());
        searchFor3072s(msgStatus.segStatus.getWarnings());
    }

    public HBCITwoStepMechanism getCurrentSecMechInfo() {
        return currentSecMechInfo;
    }

    public void setCurrentSecMechInfo(HBCITwoStepMechanism currentSecMechInfo) {
        this.currentSecMechInfo = currentSecMechInfo;
    }

    public Map<String, HBCITwoStepMechanism> getBankTwostepMechanisms() {
        return bankTwostepMechanisms;
    }

    public String getProfileMethod() {
        return "PIN";
    }

    public String getProfileVersion() {
        return currentSecMechInfo == null || currentSecMechInfo.getSecfunc().equals(Sig.SECFUNC_SIG_PT_1STEP) ? "1" :
            "2";
    }

    public boolean needUserSig() {
        return true;
    }

    public String getSysStatus() {
        return "1";
    }

    public boolean hasInstSigKey() {
        return true;
    }

    public boolean hasInstEncKey() {
        return true;
    }

    public String getInstEncKeyName() {
        return getUserId();
    }

    public String getInstEncKeyNum() {
        return "0";
    }

    public String getInstEncKeyVersion() {
        return "0";
    }

    public String getMySigKeyName() {
        return getUserId();
    }

    public String getMySigKeyNum() {
        return "0";
    }

    public String getMySigKeyVersion() {
        return "0";
    }

    public String getCryptMode() {
        // dummy-wert
        return Crypt.ENCMODE_CBC;
    }

    public String getCryptAlg() {
        // dummy-wert
        return Crypt.ENCALG_2K3DES;
    }

    public String getCryptKeyType() {
        // dummy-wert
        return Crypt.ENC_KEYTYPE_DDV;
    }

    public String getSigFunction() {
        if (getCurrentSecMechInfo() == null) {
            return SECFUNC_SIG_PT_1STEP;
        }
        return getCurrentSecMechInfo().getSecfunc();
    }

    public String getCryptFunction() {
        return Crypt.SECFUNC_ENC_PLAIN;
    }

    public String getSigAlg() {
        // dummy-wert
        return Sig.SIGALG_RSA;
    }

    public String getSigMode() {
        // dummy-wert
        return Sig.SIGMODE_ISO9796_1;
    }

    public String getHashAlg() {
        // dummy-wert
        return Sig.HASHALG_RIPEMD160;
    }

    public void setInstEncKey(HBCIKey key) {
        // TODO: implementieren für bankensignatur bei HITAN
    }

    public boolean tan2StepRequired(AbstractHBCIJob hbciJob) {
        return tan2StepRequired(hbciJob.getHBCICode());
    }

    private boolean tan2StepRequired(String jobHbciCode) {
        for (Map.Entry<String, String> bpdEntry : getBPD().entrySet()) {
            String key = bpdEntry.getKey();
            if (key.startsWith("Params") && key.substring(key.indexOf(".") + 1).startsWith("PinTanPar") && key.contains(".ParPinTan.PinTanGV") && key.endsWith(".segcode")) {
                if (jobHbciCode.equals(bpdEntry.getValue())) {
                    key = key.substring(0, key.length() - ("segcode").length()) + "needtan";
                    return getBPD().get(key).equalsIgnoreCase("J");
                }
            }
        }
        return false;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        this.proxy = proxy;
    }

    public String getProxyPass() {
        return proxypass;
    }

    public String getProxyUser() {
        return proxyuser;
    }

    public boolean tanMediaNeeded() {
        // Gibts erst ab hhd1.3, siehe
        // FinTS_3.0_Security_Sicherheitsverfahren_PINTAN_Rel_20101027_final_version.pdf, Kapitel B.4.3.1.1.1
        // Zitat: Ist in der BPD als Anzahl unterstützter aktiver TAN-Medien ein Wert > 1
        //        angegeben und ist der BPD-Wert für Bezeichnung des TAN-Mediums erforderlich = 2,
        //        so muss der Kunde z. B. im Falle des mobileTAN-Verfahrens
        //        hier die Bezeichnung seines für diesen Auftrag zu verwendenden TAN-
        //        Mediums angeben.
        // Ausserdem: "Nur bei TAN-Prozess=1, 3, 4". Das muss aber der Aufrufer pruefen. Ist mir
        // hier zu kompliziert

        HBCITwoStepMechanism secmec = getCurrentSecMechInfo();

        // Anzahl aktiver TAN-Medien ermitteln
        int nofactivetanmedia = secmec.getNofactivetanmedia();
        String needtanmedia = secmec.getNeedtanmedia();
        log.debug("nofactivetanmedia: " + nofactivetanmedia + ", needtanmedia: " + needtanmedia);

        // Ich hab Mails von Usern erhalten, bei denen die Angabe des TAN-Mediums auch
        // dann noetig war, wenn nur eine Handy-Nummer hinterlegt war. Daher logen wir
        // "nofactivetanmedia" nur, bringen die Abfrage jedoch schon bei nofactivetanmedia<2 - insofern needtanmedia=2.
        return needtanmedia.equals("2");
    }

    public String getPIN() {
        return this.pin;
    }

    public void setPIN(String pin) {
        this.pin = pin;
    }

    private void clearPIN() {
        setPIN(null);
    }

    public List<String> getUserTwostepMechanisms() {
        return this.userTwostepMechanisms;
    }

    public void setUserTwostepMechanisms(List<String> l) {
        this.userTwostepMechanisms = l;
    }

    public int getMaxGVSegsPerMsg() {
        return 1;
    }
}
