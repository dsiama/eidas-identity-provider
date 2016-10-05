package eu.eidas.auth.engine.configuration.dom;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.eidas.auth.commons.EidasErrorKey;
import eu.eidas.auth.commons.EidasErrors;
import eu.eidas.auth.commons.io.SingletonAccessor;
import eu.eidas.auth.commons.io.SingletonAccessors;
import eu.eidas.auth.engine.configuration.SamlEngineConfiguration;
import eu.eidas.auth.engine.configuration.SamlEngineConfigurationException;
import eu.eidas.samlengineconfig.CertificateConfigurationManager;
import eu.eidas.util.Preconditions;

/**
 * SamlEngineConfiguration Factory.
 * <p>
 * Remove this class in 1.2.
 *
 * @deprecated since 1.1, use {@link ProtocolEngineConfigurationFactory} instead.
 */
@Deprecated
public final class SamlEngineConfigurationFactory {

    /**
     * The logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SamlEngineConfigurationFactory.class);

    @Nonnull
    private static InstanceMap adaptToInstanceMap(@Nonnull CertificateConfigurationManager configManager)
            throws SamlEngineConfigurationException {
        if (isActiveConfigurationManager(configManager)) {
            return ConfigurationAdapter.adapt(configManager);
        }
        throw new SamlEngineConfigurationException(
                EidasErrors.get(EidasErrorKey.SAML_ENGINE_CONFIGURATION_ERROR.errorCode()),
                EidasErrors.get(EidasErrorKey.SAML_ENGINE_CONFIGURATION_ERROR.errorMessage()),
                "The given CertificateConfigurationManager is null or not active.");
    }

    @Nonnull
    public static ImmutableMap<String, SamlEngineConfiguration> getConfigurationMap(
            @Nonnull CertificateConfigurationManager configManager) throws SamlEngineConfigurationException {
        return DOMConfigurator.getConfigurationMap(adaptToInstanceMap(configManager));
    }

    private static boolean isActiveConfigurationManager(@Nullable CertificateConfigurationManager configManager) {
        return null != configManager && configManager.isActive();
    }

    @Nonnull
    private static <T> T throwConfigurationException(@Nonnull String message) throws SamlEngineConfigurationException {
        LOG.error(message);
        throw new SamlEngineConfigurationException(
                EidasErrors.get(EidasErrorKey.SAML_ENGINE_CONFIGURATION_ERROR.errorCode()),
                EidasErrors.get(EidasErrorKey.SAML_ENGINE_CONFIGURATION_ERROR.errorMessage()), message);
    }

    @Nonnull
    private final SingletonAccessor<ImmutableMap<String, SamlEngineConfiguration>> accessor;

    public SamlEngineConfigurationFactory(@Nonnull String configurationFileName, @Nullable String overrideFileName) {
        Preconditions.checkNotBlank(configurationFileName, "configurationFileName");
        accessor = new ReloadableConfigurationMap(configurationFileName, overrideFileName).getAccessor();
    }

    public SamlEngineConfigurationFactory(@Nonnull String configurationFileName) {
        this(configurationFileName, null);
    }

    public SamlEngineConfigurationFactory(@Nonnull CertificateConfigurationManager configManager)
            throws SamlEngineConfigurationException {
        Preconditions.checkNotNull(configManager, "configManager");
        accessor = SingletonAccessors.immutableAccessor(getConfigurationMap(configManager));
    }

    /**
     * Returns the corresponding configuration.
     *
     * @param instanceName the name of the instance
     */
    @Nonnull
    public SamlEngineConfiguration getConfiguration(@Nonnull String instanceName)
            throws SamlEngineConfigurationException {
        if (StringUtils.isBlank(instanceName)) {
            return throwConfigurationException("instanceName cannot be null or empty.");
        }
        String name = instanceName.trim();
        SamlEngineConfiguration samlEngineConfiguration;
        try {
            samlEngineConfiguration = accessor.get().get(name);
        } catch (IOException e) {
            throw new SamlEngineConfigurationException(
                    EidasErrors.get(EidasErrorKey.SAML_ENGINE_CONFIGURATION_ERROR.errorCode()),
                    EidasErrors.get(EidasErrorKey.SAML_ENGINE_CONFIGURATION_ERROR.errorMessage()), e);
        }
        if (null == samlEngineConfiguration) {
            return throwConfigurationException("Instance : \"" + name + "\" does not exist.");
        }
        return samlEngineConfiguration;
    }

    @Nonnull
    public SingletonAccessor<ImmutableMap<String, SamlEngineConfiguration>> getConfigurationMapAccessor() {
        return accessor;
    }
}
