/*
 * Copyright (c) 2016 by European Commission
 *
 * Licensed under the EUPL, Version 1.1 or - as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 * http://www.osor.eu/eupl/european-union-public-licence-eupl-v.1.1
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * This product combines work with different licenses. See the "NOTICE" text
 * file for details on the various modules and licenses.
 * The "NOTICE" text file is part of the distribution. Any derivative works
 * that you distribute must include a readable copy of the "NOTICE" text file.
 *
 */

package eu.eidas.auth.engine;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

import org.opensaml.Configuration;
import org.opensaml.common.SignableSAMLObject;
import org.opensaml.saml2.core.Assertion;
import org.opensaml.saml2.core.AuthnRequest;
import org.opensaml.saml2.core.Response;
import org.opensaml.xml.validation.ValidationException;
import org.opensaml.xml.validation.ValidatorSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import eu.eidas.auth.commons.EidasErrorKey;
import eu.eidas.auth.commons.EidasErrors;
import eu.eidas.auth.commons.protocol.IAuthenticationRequest;
import eu.eidas.auth.commons.protocol.IAuthenticationResponse;
import eu.eidas.auth.commons.protocol.IRequestMessage;
import eu.eidas.auth.commons.protocol.IResponseMessage;
import eu.eidas.auth.commons.protocol.impl.BinaryRequestMessage;
import eu.eidas.auth.commons.protocol.impl.BinaryResponseMessage;
import eu.eidas.auth.engine.configuration.ProtocolConfigurationAccessor;
import eu.eidas.auth.engine.xml.opensaml.CertificateUtil;
import eu.eidas.auth.engine.xml.opensaml.CorrelatedResponse;
import eu.eidas.auth.engine.xml.opensaml.XmlSchemaUtil;
import eu.eidas.engine.exceptions.EIDASSAMLEngineException;

/**
 * The ProtocolEngine is responsible for creating Saml Request and Response from their binary representations and for
 * creating binary representations from Saml Request and Response objects.
 *
 * @since 1.1
 */
public class ProtocolEngine extends AbstractProtocolEngine implements ProtocolEngineI {

    public static final String ATTRIBUTE_EMPTY_LITERAL = "Attribute name is null or empty.";

    /**
     * The LOG.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ProtocolEngine.class);

    private static final CharsetEncoder LATIN_1_CHARSET_ENCODER = Charset.forName("ISO-8859-1").newEncoder();

    public static boolean needsTransliteration(String v) {
        return !LATIN_1_CHARSET_ENCODER.canEncode(v);
    }

    private static void validateSaml2CoreSchema(SignableSAMLObject samlObject) throws EIDASSAMLEngineException {
        LOG.trace("Validate Schema.");
        ValidatorSuite validatorSuite = Configuration.getValidatorSuite("saml2-core-schema-validator");
        try {
            validatorSuite.validate(samlObject);
        } catch (ValidationException e) {
            LOG.info(SAML_EXCHANGE, "BUSINESS EXCEPTION : ValidationException.", e.getMessage());
            LOG.debug(SAML_EXCHANGE, "BUSINESS EXCEPTION : ValidationException.", e);
            throw new EIDASSAMLEngineException(EidasErrors.get(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode()),
                                               EidasErrors.get(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorMessage()),
                                               e);
        }
    }

    /**
     * Constructs a new Saml engine instance.
     *
     * @param configurationAccessor the accessor to the configuration of this instance.
     */
    public ProtocolEngine(@Nonnull ProtocolConfigurationAccessor configurationAccessor) {
        super(configurationAccessor);
    }

    /**
     * Validate parameters from response.
     *
     * @param request the request
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    private void checkRequestSanity(IAuthenticationRequest request) throws EIDASSAMLEngineException {
        getProtocolProcessor().checkRequestSanity(request);
    }

    /**
     * Validate parameters from response.
     *
     * @param response the response authentication request
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    private void checkResponseSanity(IAuthenticationResponse response) throws EIDASSAMLEngineException {
        if (response.getAttributes() == null || response.getAttributes().isEmpty()) {
            LOG.error(SAML_EXCHANGE, "No attribute values in response.");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "No attribute values in response.");
        }
    }

    /**
     * Generate the authentication request.
     *
     * @param request the request that contain all parameters for generate an authentication request.
     * @return the EIDAS authentication request that has been processed.
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    @Override
    @Nonnull
    public IRequestMessage generateRequestMessage(@Nonnull IAuthenticationRequest request,
                                                  @Nonnull String serviceIssuer) throws EIDASSAMLEngineException {
        LOG.trace("Generate SAMLAuthnRequest.");
        if (null == request) {
            LOG.debug(SAML_EXCHANGE, "Sign and Marshall - null input");
            LOG.info(SAML_EXCHANGE, "BUSINESS EXCEPTION : Sign and Marshall -null input");
            throw new EIDASSAMLEngineException(EidasErrorKey.INTERNAL_ERROR.errorCode(),
                                               EidasErrorKey.INTERNAL_ERROR.errorMessage());
        }

        // Validate mandatory parameters
        AuthnRequest samlRequest = getProtocolProcessor().marshallRequest(request, serviceIssuer, getCoreProperties());
        IAuthenticationRequest updatedRequest =
                getProtocolProcessor().unmarshallRequest(request.getCitizenCountryCode(), samlRequest,
                                                         request.getOriginCountryCode());
        try {
            byte[] bytes = signAndMarshallRequest(samlRequest);
            return new BinaryRequestMessage(updatedRequest, bytes);
        } catch (EIDASSAMLEngineException e) {
            LOG.debug(SAML_EXCHANGE, "Sign and Marshall.", e);
            LOG.info(SAML_EXCHANGE, "BUSINESS EXCEPTION : Sign and Marshall.", e);
            throw new EIDASSAMLEngineException(EidasErrorKey.INTERNAL_ERROR.errorCode(),
                                               EidasErrorKey.INTERNAL_ERROR.errorMessage(), e);
        }
    }

    /**
     * Generate authentication response in one of the supported formats.
     *
     * @param request the request
     * @param response the authentication response from the IdP
     * @param ipAddress the IP address
     * @param signAssertion whether to sign the attribute assertion
     * @return the authentication response
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    @Override
    @Nonnull
    public IResponseMessage generateResponseMessage(@Nonnull IAuthenticationRequest request,
                                                    @Nonnull IAuthenticationResponse response,
                                                    boolean signAssertion,
                                                    @Nonnull String ipAddress) throws EIDASSAMLEngineException {
        LOG.trace("generateResponseMessage");
        // Validate parameters
        validateParamResponse(request, response);

        Response samlResponse =
                getProtocolProcessor().marshallResponse(request, response, ipAddress, getCoreProperties());

        // update the assertions in the response to signed assertions if needed:
        if (signAssertion) {
            List<Assertion> assertions = samlResponse.getAssertions();
            List<Assertion> signedAssertions = new ArrayList<>(assertions.size());
            for (Assertion assertion : assertions) {
                try {
                    Assertion signedAssertion = signAssertion(assertion);
                    signedAssertions.add(signedAssertion);
                } catch (EIDASSAMLEngineException e) {
                    LOG.error(SAML_EXCHANGE, "BUSINESS EXCEPTION : cannot sign assertion: " + e, e);
                    throw e;
                }
            }
            samlResponse.getAssertions().clear();
            samlResponse.getAssertions().addAll(signedAssertions);
        }
        return encryptAndSignAndMarshallResponse(request, response, samlResponse);

    }

    private IResponseMessage encryptAndSignAndMarshallResponse(@Nonnull IAuthenticationRequest request,
                                                               @Nonnull IAuthenticationResponse response,
                                                               Response samlResponse) throws EIDASSAMLEngineException {
        // encrypt and sign the whole response:
        try {
            byte[] responseBytes = signAndMarshallResponse(request, samlResponse);
            return new BinaryResponseMessage(response, responseBytes);
        } catch (EIDASSAMLEngineException e) {
            LOG.error(SAML_EXCHANGE, "BUSINESS EXCEPTION : Sign and Marshall: " + e, e);
            throw new EIDASSAMLEngineException(EidasErrorKey.INTERNAL_ERROR.errorCode(),
                                               EidasErrorKey.INTERNAL_ERROR.errorMessage(), e);
        }
    }

    /**
     * Generates an authentication response error message.
     *
     * @param request the request
     * @param response the response
     * @param ipAddress the IP address
     * @return the authentication response
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    @Override
    @Nonnull
    public IResponseMessage generateResponseErrorMessage(@Nonnull IAuthenticationRequest request,
                                                         @Nonnull IAuthenticationResponse response,
                                                         @Nonnull String ipAddress) throws EIDASSAMLEngineException {

        Response responseFail =
                getProtocolProcessor().marshallErrorResponse(request, response, ipAddress, getCoreProperties());

        IAuthenticationResponse authenticationResponse =
                getProtocolProcessor().unmarshallErrorResponse(response, responseFail, ipAddress, getCoreProperties());

        LOG.trace("Sign and Marshall ResponseFail.");
        return encryptAndSignAndMarshallResponse(request, authenticationResponse, responseFail);
    }

    /**
     * Unmarshalls the given bytes into a SAML Request.
     *
     * @param tokenSaml the SAML request bytes
     * @return the SAML request instance
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    public AuthnRequest unmarshallRequest(@Nonnull byte[] requestBytes) throws EIDASSAMLEngineException {
        LOG.trace("Validate request bytes.");

        if (null == requestBytes) {
            LOG.info(SAML_EXCHANGE, "BUSINESS EXCEPTION : Saml request bytes are null.");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "Saml request bytes are null.");
        }

        Document document = XmlSchemaUtil.validateSamlSchema(requestBytes);
        AuthnRequest request = (AuthnRequest) unmarshall(document);
        request = validateSignature(request);

        validateSaml2CoreSchema(request);

        validateRequestWithValidatorSuite(request);

        return request;
    }

    /**
     * Process and validates the authentication request.
     *
     * @param requestBytes the token SAML
     * @return the authentication request
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    @Override
    @Nonnull
    public IAuthenticationRequest unmarshallRequestAndValidate(@Nonnull byte[] requestBytes,
                                                               @Nonnull String citizenCountryCode)
            throws EIDASSAMLEngineException {
        LOG.trace("processValidateRequestToken");

        if (null == requestBytes) {
            LOG.info(SAML_EXCHANGE, "BUSINESS EXCEPTION : Saml authentication request is null.");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "Saml authentication request is null.");
        }
        AuthnRequest originalSamlRequest = unmarshallRequest(requestBytes);
        LOG.trace("Generate EIDASAuthnSamlRequest.");

        String originCountryCode = (originalSamlRequest.getSignature() != null) ? CertificateUtil.getCountry(
                originalSamlRequest.getSignature().getKeyInfo()) : null;

        IAuthenticationRequest authenticationRequest =
                getProtocolProcessor().unmarshallRequest(citizenCountryCode, originalSamlRequest, originCountryCode);

        checkRequestSanity(authenticationRequest);

        return authenticationRequest;
    }

    /**
     * Unmarshalls the given bytes into a SAML Response.
     *
     * @param tokenSaml the SAML response bytes
     * @return the SAML response instance
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    @Override
    @Nonnull
    public Correlated unmarshallResponse(@Nonnull byte[] responseBytes) throws EIDASSAMLEngineException {
        LOG.trace("Validate response bytes.");

        if (null == responseBytes) {
            LOG.info(SAML_EXCHANGE, "BUSINESS EXCEPTION : Saml response bytes are null.");
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               "Saml response bytes are null.");
        }

        LOG.trace("Generate SAML Response.");

        Document document = XmlSchemaUtil.validateSamlSchema(responseBytes);
        Response response = (Response) unmarshall(document);
        response = validateSignatureAndDecryptAndValidateAssertionSignatures(response);

        validateSaml2CoreSchema(response);

        validateResponseWithValidatorSuite(response);

        return new CorrelatedResponse(response);
    }

    /**
     * Process and validates the authentication response.
     *
     * @param responseBytes the token SAML
     * @param userIpAddress the user IP
     * @return the authentication response
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    @Override
    @Nonnull
    public IAuthenticationResponse unmarshallResponseAndValidate(@Nonnull byte[] responseBytes,
                                                                 @Nonnull String userIpAddress,
                                                                 long skewTimeInMillis,
                                                                 @Nullable String audienceRestriction)
            throws EIDASSAMLEngineException {
        Correlated samlResponse = unmarshallResponse(responseBytes);

        return validateUnmarshalledResponse(samlResponse, userIpAddress, skewTimeInMillis, audienceRestriction);
    }

    private void validateAssertionSignatures(Response response) throws EIDASSAMLEngineException {
        try {
            boolean validateSign = getCoreProperties().isValidateSignature();
            if (validateSign) {
                X509Certificate signatureCertificate =
                        getProtocolProcessor().getResponseSignatureCertificate(response.getIssuer().getValue());

                ImmutableSet<X509Certificate> trustedCertificates =
                        null == signatureCertificate ? null : ImmutableSet.of(signatureCertificate);
                for (Assertion assertion : response.getAssertions()) {
                    if (assertion.isSigned() && null != assertion.getSignature()) {
                        getSigner().validateSignature(assertion, trustedCertificates);
                    }
                }
            }
        } catch (EIDASSAMLEngineException e) {
            EIDASSAMLEngineException exc =
                    new EIDASSAMLEngineException(EidasErrorKey.INVALID_ASSERTION_SIGNATURE.errorCode(),
                                                 EidasErrorKey.INVALID_ASSERTION_SIGNATURE.errorMessage(), e);
            throw exc;
        }
    }

    /**
     * Validate parameters from response.
     *
     * @param request the request
     * @param response the response authentication request
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    private void validateParamResponse(IAuthenticationRequest request, IAuthenticationResponse response)
            throws EIDASSAMLEngineException {
        LOG.trace("Validate parameters response.");
        checkRequestSanity(request);
        checkResponseSanity(response);
    }

    private void validateRequestWithValidatorSuite(@Nonnull AuthnRequest request) throws EIDASSAMLEngineException {
        String suiteId = getProtocolProcessor().getRequestValidatorId();
        LOG.trace("Validate AuthnRequest with suite: " + suiteId);
        ValidatorSuite suite = Configuration.getValidatorSuite(suiteId);
        try {
            suite.validate(request);
            LOG.debug("AuthnRequest validation with " + suiteId + " succeeded.");
        } catch (ValidationException e) {
            LOG.error(SAML_EXCHANGE, "BUSINESS EXCEPTION : validate AuthnRequest: " + e, e);
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorMessage(), e);
        }
    }

    private void validateResponseWithValidatorSuite(@Nonnull Response response) throws EIDASSAMLEngineException {
        String suiteId = getProtocolProcessor().getResponseValidatorId();
        LOG.trace("Validate Response with suite: " + suiteId);
        ValidatorSuite suite = Configuration.getValidatorSuite(suiteId);
        try {
            suite.validate(response);
            LOG.debug("Response validation with " + suiteId + " succeeded.");
        } catch (ValidationException e) {
            LOG.error(SAML_EXCHANGE, "BUSINESS EXCEPTION : validate Response: " + e, e);
            throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(),
                                               EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorMessage(), e);
        }
    }

    private AuthnRequest validateSignature(AuthnRequest request) throws EIDASSAMLEngineException {
        boolean validateSign = getCoreProperties().isValidateSignature();
        if (validateSign) {
            LOG.trace("Validate request Signature.");
            if (!request.isSigned() || null == request.getSignature()) {
                throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(), "No signature");
            }
            if (null == request.getIssuer()) {
                throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(), "The issuer cannot be null");
            }
            try {
                X509Certificate signatureCertificate =
                        getProtocolProcessor().getRequestSignatureCertificate(request.getIssuer().getValue());
                return getSigner().validateSignature(request, null == signatureCertificate ? null : ImmutableSet.of(
                        signatureCertificate));
            } catch (EIDASSAMLEngineException e) {
                LOG.error(SAML_EXCHANGE, "BUSINESS EXCEPTION : SAMLEngineException validateSignature: " + e,
                          e.getMessage(), e);
                throw e;
            }
        }
        return request;
    }

    private Response validateSignatureAndDecryptAndValidateAssertionSignatures(Response response)
            throws EIDASSAMLEngineException {
        boolean validateSign = getCoreProperties().isValidateSignature();
        if (validateSign) {
            LOG.trace("Validate response Signature.");
            if (!response.isSigned() || null == response.getSignature()) {
                throw new EIDASSAMLEngineException(EidasErrorKey.MESSAGE_VALIDATION_ERROR.errorCode(), "No signature");
            }

            String country = CertificateUtil.getCountry(response.getSignature().getKeyInfo());
            LOG.debug(SAML_EXCHANGE, "Response received from country: " + country);
            try {
                response = validateSignatureAndDecrypt(response);

                validateAssertionSignatures(response);
            } catch (EIDASSAMLEngineException e) {
                LOG.error(SAML_EXCHANGE, "BUSINESS EXCEPTION : SAMLEngineException validateSignature: " + e,
                          e.getMessage(), e);
                throw e;
            }
        }
        return response;
    }

    /**
     * Validate authentication response.
     *
     * @param samlResponse the token SAML
     * @param userIpAddress the user IP
     * @param skewTimeInMillis the skew time
     * @return the authentication response
     * @throws EIDASSAMLEngineException the EIDASSAML engine exception
     */
    @Nonnull
    @Override
    public IAuthenticationResponse validateUnmarshalledResponse(@Nonnull Correlated unmarshalledResponse,
                                                                @Nonnull String userIpAddress,
                                                                long skewTimeInMillis,
                                                                @Nullable String audienceRestriction)
            throws EIDASSAMLEngineException {

        Response response = ((CorrelatedResponse) unmarshalledResponse).getResponse();

        return getProtocolProcessor().unmarshallResponse(response, getCoreProperties().isIpValidation(), userIpAddress,
                                                         skewTimeInMillis, getClock().getCurrentTime(),
                                                         audienceRestriction);
    }
}
