package eu.eidas.auth.engine.xml.opensaml;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.opensaml.saml2.core.Assertion;
import org.opensaml.saml2.core.AttributeStatement;
import org.opensaml.saml2.core.Audience;
import org.opensaml.saml2.core.AudienceRestriction;
import org.opensaml.saml2.core.Conditions;
import org.opensaml.saml2.core.Response;
import org.opensaml.saml2.core.Status;
import org.opensaml.saml2.core.StatusCode;
import org.opensaml.saml2.core.Subject;
import org.opensaml.saml2.core.SubjectConfirmation;
import org.opensaml.saml2.core.SubjectConfirmationData;
import org.opensaml.xml.XMLObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.eidas.auth.commons.EidasErrorKey;
import eu.eidas.auth.commons.light.IResponseStatus;
import eu.eidas.auth.commons.light.impl.ResponseStatus;
import eu.eidas.auth.engine.AbstractProtocolEngine;
import eu.eidas.engine.exceptions.EIDASSAMLEngineException;

/**
 * Utility class pertaining to the Response.
 *
 * @since 1.1
 */
public final class ResponseUtil {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseUtil.class);

    @Nonnull
    public static IResponseStatus extractResponseStatus(@Nonnull Response samlResponse) {
        ResponseStatus.Builder builder = ResponseStatus.builder();

        Status status = samlResponse.getStatus();
        StatusCode statusCode = status.getStatusCode();
        String statusCodeValue = statusCode.getValue();
        builder.statusCode(statusCodeValue);
        builder.failure(isFailureStatusCode(statusCodeValue));

        // Subordinate code.
        StatusCode subStatusCode = statusCode.getStatusCode();
        if (subStatusCode != null) {
            builder.subStatusCode(subStatusCode.getValue());
        }

        if (status.getStatusMessage() != null) {
            builder.statusMessage(status.getStatusMessage().getMessage());
        }

        return builder.build();
    }

    /**
     * Extracts the verified assertion from the given response.
     *
     * @param samlResponse the SAML response
     * @param userIpAddress the user IP address
     * @return the assertion
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    @Nonnull
    public static Assertion extractVerifiedAssertion(@Nonnull Response samlResponse,
                                                     boolean verifyBearerIpAddress,
                                                     @Nullable String userIpAddress,
                                                     long skewTimeInMillis,
                                                     @Nonnull DateTime now,
                                                     @Nullable String audienceRestriction)
            throws EIDASSAMLEngineException {
        // Exist only one Assertion
        if (samlResponse.getAssertions() == null || samlResponse.getAssertions().isEmpty()) {
            //in replace of throwing  EIDASSAMLEngineException("Assertion is null or empty.")
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE,
                      "BUSINESS EXCEPTION : Assertion is null, empty or the response is encrypted and decryption is not active.");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "Assertion is null, empty or the response is encrypted and decryption is not active.");
        }

        Assertion assertion = samlResponse.getAssertions().get(0);

        verifyAssertion(assertion, verifyBearerIpAddress, userIpAddress, skewTimeInMillis, now, audienceRestriction);

        return assertion;
    }

    public static boolean isFailure(@Nonnull IResponseStatus responseStatus) {
        return responseStatus.isFailure() || isFailureStatusCode(responseStatus.getStatusCode());
    }

    public static boolean isFailureStatusCode(@Nonnull String statusCodeValue) {
        return !StatusCode.SUCCESS_URI.equals(statusCodeValue);
    }

    public static void verifyAssertion(@Nonnull Assertion assertion,
                                       boolean verifyBearerIpAddress,
                                       @Nonnull String userIpAddress,
                                       long skewTimeInMillis,
                                       @Nonnull DateTime now,
                                       @Nullable String audienceRestriction) throws EIDASSAMLEngineException {
        if (verifyBearerIpAddress) {
            Subject subject = assertion.getSubject();
            verifyBearerIpAddress(subject, userIpAddress);
        }

        // Applying skew time conditions before testing it
        DateTime skewedNotBefore =
                new DateTime(assertion.getConditions().getNotBefore().getMillis() - skewTimeInMillis, DateTimeZone.UTC);
        DateTime skewedNotOnOrAfter =
                new DateTime(assertion.getConditions().getNotOnOrAfter().getMillis() + skewTimeInMillis,
                             DateTimeZone.UTC);
        LOG.debug(AbstractProtocolEngine.SAML_EXCHANGE, "skewTimeInMillis : {}", skewTimeInMillis);
        LOG.debug(AbstractProtocolEngine.SAML_EXCHANGE, "skewedNotBefore       : {}", skewedNotBefore);
        LOG.debug(AbstractProtocolEngine.SAML_EXCHANGE, "skewedNotOnOrAfter    : {}", skewedNotOnOrAfter);
        assertion.getConditions().setNotBefore(skewedNotBefore);
        assertion.getConditions().setNotOnOrAfter(skewedNotOnOrAfter);

        Conditions conditions = assertion.getConditions();
        verifyConditions(conditions, now, audienceRestriction);
    }

    public static void verifyAudienceRestriction(@Nonnull Conditions conditions, @Nonnull String audienceRestriction)
            throws EIDASSAMLEngineException {
        if (conditions.getAudienceRestrictions() == null || conditions.getAudienceRestrictions().isEmpty()) {
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE, "BUSINESS EXCEPTION : AudienceRestriction must be present");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "AudienceRestriction must be present");
        }
        AudienceRestriction firstAudienceRestriction = conditions.getAudienceRestrictions().get(0);
        List<Audience> audiences = firstAudienceRestriction.getAudiences();
        if (CollectionUtils.isEmpty(audiences)) {
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE, "BUSINESS EXCEPTION : Audiences must not be empty");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "Audiences must not be empty");
        }
        boolean audienceAllowed = false;
        for (final Audience audience : audiences) {
            if (audience.getAudienceURI().equals(audienceRestriction)) {
                audienceAllowed = true;
                break;
            }
        }
        if (!audienceAllowed) {
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE,
                      "BUSINESS EXCEPTION : audience \"" + audienceRestriction + "\" is not allowed");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "Audience \"" + audienceRestriction + "\" is not allowed");
        }
    }

    public static void verifyBearerIpAddress(@Nonnull Subject subject, @Nonnull String userIpAddress)
            throws EIDASSAMLEngineException {
        LOG.trace("Verified method Bearer");

        if (null == subject) {
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE, "BUSINESS EXCEPTION : subject is null.");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(), "subject is null.");
        }
        List<SubjectConfirmation> subjectConfirmations = subject.getSubjectConfirmations();
        if (null == subjectConfirmations || subjectConfirmations.isEmpty()) {
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE,
                      "BUSINESS EXCEPTION : SubjectConfirmations are null or empty.");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "SubjectConfirmations are null or empty.");
        }
        for (final SubjectConfirmation element : subjectConfirmations) {
            boolean isBearer = SubjectConfirmation.METHOD_BEARER.equals(element.getMethod());
            SubjectConfirmationData subjectConfirmationData = element.getSubjectConfirmationData();
            if (null == subjectConfirmationData) {
                LOG.error(AbstractProtocolEngine.SAML_EXCHANGE,
                          "BUSINESS EXCEPTION : subjectConfirmationData is null.");
                throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                                   EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                                   "subjectConfirmationData is null.");
            }
            String address = subjectConfirmationData.getAddress();
            if (isBearer) {
                if (StringUtils.isBlank(userIpAddress)) {
                    LOG.error(AbstractProtocolEngine.SAML_EXCHANGE,
                              "BUSINESS EXCEPTION : browser_ip is null or empty.");
                    throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                                       EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                                       "browser_ip is null or empty.");
                } else if (StringUtils.isBlank(address)) {
                    LOG.error(AbstractProtocolEngine.SAML_EXCHANGE,
                              "BUSINESS EXCEPTION : token_ip attribute is null or empty.");
                    throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                                       EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                                       "token_ip attribute is null or empty.");
                }
            }
            boolean ipEqual = address.equals(userIpAddress);
            // Validation ipUser
            if (!ipEqual) {
                LOG.error(AbstractProtocolEngine.SAML_EXCHANGE,
                          "BUSINESS EXCEPTION : SubjectConfirmation BEARER: IPs doesn't match : token_ip [{}] browser_ip [{}]",
                          address, userIpAddress);
                throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                                   EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                                   "IPs doesn't match : token_ip (" + address + ") browser_ip ("
                                                           + userIpAddress + ")");
            }
        }
    }

    public static void verifyConditions(@Nonnull Conditions conditions,
                                        @Nonnull DateTime now,
                                        @Nullable String audienceRestriction) throws EIDASSAMLEngineException {
        if (null != audienceRestriction) {
            verifyAudienceRestriction(conditions, audienceRestriction);
        }

        verifyOneTimeUse(conditions);

        verifyTimeConditions(conditions, now);
    }

    public static void verifyOneTimeUse(@Nonnull Conditions conditions) throws EIDASSAMLEngineException {
        if (conditions.getOneTimeUse() == null) {
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE, "BUSINESS EXCEPTION : OneTimeUse must be present");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "OneTimeUse must be present");
        }
    }

    public static void verifyTimeConditions(@Nonnull Conditions conditions, @Nonnull DateTime now)
            throws EIDASSAMLEngineException {
        LOG.debug("serverDate            : " + now);
        DateTime notBefore = conditions.getNotBefore();
        if (notBefore == null) {
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE, "BUSINESS EXCEPTION : NotBefore must be present");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "NotBefore must be present");
        }
        if (notBefore.isAfter(now)) {
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE,
                      "BUSINESS EXCEPTION : Current time is before NotBefore condition");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "Current time is before NotBefore condition");
        }
        DateTime notOnOrAfter = conditions.getNotOnOrAfter();
        if (notOnOrAfter == null) {
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE, "BUSINESS EXCEPTION : NotOnOrAfter must be present");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "NotOnOrAfter must be present");
        }
        if (notOnOrAfter.isBeforeNow()) {
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE,
                      "BUSINESS EXCEPTION : Current time is after NotOnOrAfter condition");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "Current time is after NotOnOrAfter condition");
        }
        if (notOnOrAfter.isBefore(now)) {
            LOG.error(AbstractProtocolEngine.SAML_EXCHANGE,
                      "BUSINESS EXCEPTION : Token date expired (getNotOnOrAfter =  " + notOnOrAfter + ", server_date: "
                              + now + ")");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "Token date expired (getNotOnOrAfter =  " + notOnOrAfter
                                                       + " ), server_date: " + now);
        }
    }

    @Nonnull
    public static AttributeStatement findAttributeStatement(@Nonnull Assertion assertion) throws EIDASSAMLEngineException {
        AttributeStatement attributeStatement = findAttributeStatementNullable(assertion);
        if (null != attributeStatement) {
            return attributeStatement;
        }

        LOG.error(AbstractProtocolEngine.SAML_EXCHANGE, "BUSINESS EXCEPTION : AttributeStatement not present.");
        throw new EIDASSAMLEngineException(EidasErrorKey.INTERNAL_ERROR.errorCode(),
                                           EidasErrorKey.INTERNAL_ERROR.errorCode(), "AttributeStatement not present.");
    }

    @Nullable
    public static AttributeStatement findAttributeStatementNullable(@Nonnull Assertion assertion) {
        List<XMLObject> orderedChildren = assertion.getOrderedChildren();
        // Search the attribute statement.
        for (XMLObject child : orderedChildren) {
            if (child instanceof AttributeStatement) {
                return (AttributeStatement) child;
            }
        }
        return null;
    }

    private ResponseUtil() {
    }
}
