
package org.kapott.hbci.sepa.jaxb.pain_008_001_02;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java-Klasse für FinancialInstitutionIdentificationSEPA3 complex type.
 * 
 * <p>Das folgende Schemafragment gibt den erwarteten Content an, der in dieser Klasse enthalten ist.
 * 
 * <pre>
 * &lt;complexType name="FinancialInstitutionIdentificationSEPA3">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence>
 *         &lt;choice>
 *           &lt;element name="BIC" type="{urn:iso:std:iso:20022:tech:xsd:pain.008.001.02}BICIdentifier"/>
 *           &lt;element name="Othr" type="{urn:iso:std:iso:20022:tech:xsd:pain.008.001.02}OthrIdentification"/>
 *         &lt;/choice>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "FinancialInstitutionIdentificationSEPA3", namespace = "urn:iso:std:iso:20022:tech:xsd:pain.008.001.02", propOrder = {
    "bic",
    "othr"
})
public class FinancialInstitutionIdentificationSEPA3 {

    @XmlElement(name = "BIC", namespace = "urn:iso:std:iso:20022:tech:xsd:pain.008.001.02")
    protected String bic;
    @XmlElement(name = "Othr", namespace = "urn:iso:std:iso:20022:tech:xsd:pain.008.001.02")
    protected OthrIdentification othr;

    /**
     * Ruft den Wert der bic-Eigenschaft ab.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getBIC() {
        return bic;
    }

    /**
     * Legt den Wert der bic-Eigenschaft fest.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setBIC(String value) {
        this.bic = value;
    }

    /**
     * Ruft den Wert der othr-Eigenschaft ab.
     * 
     * @return
     *     possible object is
     *     {@link OthrIdentification }
     *     
     */
    public OthrIdentification getOthr() {
        return othr;
    }

    /**
     * Legt den Wert der othr-Eigenschaft fest.
     * 
     * @param value
     *     allowed object is
     *     {@link OthrIdentification }
     *     
     */
    public void setOthr(OthrIdentification value) {
        this.othr = value;
    }

}