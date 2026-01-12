package io.jafar.shell.llm.privacy;

import io.jafar.shell.llm.LLMConfig;
import io.jafar.shell.llm.LLMException;

/**
 * Enforces privacy policies for LLM interactions. Blocks requests that violate configured privacy
 * settings and provides user confirmations when needed.
 */
public class PrivacyPolicy {

  private final LLMConfig.PrivacySettings settings;

  /**
   * Creates a privacy policy enforcer.
   *
   * @param settings privacy settings
   */
  public PrivacyPolicy(LLMConfig.PrivacySettings settings) {
    this.settings = settings;
  }

  /**
   * Checks if a cloud provider can be used based on privacy mode.
   *
   * @param providerType provider type
   * @return true if allowed
   */
  public boolean allowCloudProvider(LLMConfig.ProviderType providerType) {
    // Local and mock providers are always allowed
    if (providerType == LLMConfig.ProviderType.LOCAL
        || providerType == LLMConfig.ProviderType.MOCK) {
      return true;
    }

    // Cloud providers depend on privacy mode
    return switch (settings.mode()) {
      case LOCAL_ONLY -> false;
      case CLOUD_WITH_CONFIRM, SMART -> true;
    };
  }

  /**
   * Enforces privacy policy for a provider.
   *
   * @param providerType provider type
   * @throws LLMException if provider is not allowed
   */
  public void enforcePolicy(LLMConfig.ProviderType providerType) throws LLMException {
    if (!allowCloudProvider(providerType)) {
      throw new LLMException(
          LLMException.ErrorType.PROVIDER_UNAVAILABLE,
          "Cloud provider blocked by privacy policy (mode: "
              + settings.mode()
              + "). Use local provider or change privacy.mode in config.");
    }
  }

  /**
   * Checks if user confirmation is needed for cloud requests.
   *
   * @param providerType provider type
   * @return true if confirmation needed
   */
  public boolean needsConfirmation(LLMConfig.ProviderType providerType) {
    if (providerType == LLMConfig.ProviderType.LOCAL
        || providerType == LLMConfig.ProviderType.MOCK) {
      return false;
    }

    return settings.mode() == LLMConfig.PrivacyMode.CLOUD_WITH_CONFIRM;
  }

  /**
   * Determines if a query should use local or cloud provider in SMART mode.
   *
   * @param queryComplexity estimated complexity (0.0 = simple, 1.0 = complex)
   * @return true to use cloud
   */
  public boolean useCloudForQuery(double queryComplexity) {
    if (settings.mode() != LLMConfig.PrivacyMode.SMART) {
      return allowCloudProvider(LLMConfig.ProviderType.OPENAI);
    }

    // In SMART mode, use cloud for complex queries (>0.7)
    return queryComplexity > 0.7;
  }

  /**
   * Gets the privacy mode.
   *
   * @return privacy mode
   */
  public LLMConfig.PrivacyMode getMode() {
    return settings.mode();
  }

  /**
   * Checks if audit logging is enabled.
   *
   * @return true if enabled
   */
  public boolean isAuditEnabled() {
    return settings.auditEnabled();
  }
}
