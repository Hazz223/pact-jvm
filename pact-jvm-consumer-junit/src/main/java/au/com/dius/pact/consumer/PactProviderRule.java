package au.com.dius.pact.consumer;

import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.model.MockHttpsProviderConfig;
import au.com.dius.pact.model.MockProviderConfig;
import au.com.dius.pact.model.MockProviderConfig$;
import au.com.dius.pact.model.PactFragment;
import au.com.dius.pact.model.PactSpecVersion;
import au.com.dius.pact.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * A junit rule that wraps every test annotated with {@link PactVerification}.
 * Before each test, a mock server will be setup at given port/host that will provide mocked responses for the given
 * provider. After each test, it will be teared down.
 *
 * If no host is given, it will default to localhost. If no port is given, it will default to a random port.
 */
public class PactProviderRule extends ExternalResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(PactProviderRule.class);

    private static final VerificationResult PACT_VERIFIED = PactVerified$.MODULE$;
    private final String provider;
    private final Object target;
    private MockProviderConfig config;
    private Map <String, PactFragment> fragments;

    /**
     * Creates a mock provider by the given name
     * @param provider Provider name to mock
     * @param host Host to bind to. Defaults to localhost
     * @param port Port to bind to. Defaults to a random port.
     * @param pactVersion Pact specification version
     * @param target Target test to apply this rule to.
     */
    public PactProviderRule(String provider, String host, Integer port, PactSpecVersion pactVersion, Object target) {
        this.provider = provider;
        this.target = target;
        if (host == null && port == null) {
            config = MockProviderConfig$.MODULE$.createDefault(pactVersion);
        } else {
            config = MockProviderConfig$.MODULE$.apply(port, host, pactVersion);
        }
    }

    /**
     * Creates a mock provider by the given name
     * @param provider Provider name to mock
     * @param host Host to bind to. Defaults to localhost
     * @param port Port to bind to. Defaults to a random port.
     * @param https Boolean flag to control starting HTTPS or HTTP mock server
     * @param pactVersion Pact specification version
     * @param target Target test to apply this rule to.
     */
    public PactProviderRule(String provider, String host, Integer port, boolean https, PactSpecVersion pactVersion,
                            Object target) {
      this(provider, host, port, pactVersion, target);
      if (https) {
        config = new MockHttpsProviderConfig(port, host, pactVersion);
      }
    }

    /**
     * Creates a mock provider by the given name
     * @param provider Provider name to mock
     * @param host Host to bind to. Defaults to localhost
     * @param port Port to bind to. Defaults to a random port.
     * @param target Target test to apply this rule to.
     */
    public PactProviderRule(String provider, String host, Integer port, Object target) {
        this(provider, host, port, PactSpecVersion.V2, target);
    }

    /**
     * Creates a mock provider by the given name. Binds to localhost and a random port.
     * @param provider Provider name to mock
     * @param target Target test to apply this rule to.
     */
    public PactProviderRule(String provider, Object target) {
        this(provider, null, null, PactSpecVersion.V2, target);
    }

    /**
     * Creates a mock provider by the given name. Binds to localhost and a random port.
     * @param provider Provider name to mock
     * @param target Target test to apply this rule to.
     */
    public PactProviderRule(String provider, PactSpecVersion pactSpecVersion, Object target) {
        this(provider, null, null, pactSpecVersion, target);
    }

    public MockProviderConfig getConfig() {
        return config;
    }

    @Override
    public Statement apply(final Statement base, final Description description) {
        return new Statement() {

            @Override
            public void evaluate() throws Throwable {
                PactVerifications pactVerifications = description.getAnnotation(PactVerifications.class);
                if (pactVerifications != null) {
                    evaluatePactVerifications(pactVerifications, base);
                    return;
                }

                PactVerification pactDef = description.getAnnotation(PactVerification.class);
                // no pactVerification? execute the test normally
                if (pactDef == null) {
                    base.evaluate();
                    return;
                }

                Map<String, PactFragment> pacts = getPacts(pactDef.fragment());
                PactFragment fragment = null;
                if (pactDef.value().length == 1 && StringUtils.isEmpty(pactDef.value()[0])) {
                    fragment = pacts.values().iterator().next();
                } else {
                    for (String provider: pactDef.value()) {
                        if (fragment == null && pacts.containsKey(provider)) {
                            fragment = pacts.get(provider);
                        }
                    }
                }
                if (fragment == null) {
                    base.evaluate();
                    return;
                }

                VerificationResult result = runPactTest(base, fragment);
                validateResult(result, pactDef);
            }
        };
    }

    private void evaluatePactVerifications(PactVerifications pactVerifications, Statement base) throws Throwable {
        Optional<PactVerification> possiblePactVerification = findPactVerification(pactVerifications);
        if (!possiblePactVerification.isPresent()) {
            base.evaluate();
            return;
        }

        PactVerification pactVerification = possiblePactVerification.get();
        Optional<Method> possiblePactMethod = findPactMethod(pactVerification);
        if (!possiblePactMethod.isPresent()) {
            throw new UnsupportedOperationException("Could not find method with @Pact for the provider " + provider);
        }

        Method method = possiblePactMethod.get();
        Pact pact = method.getAnnotation(Pact.class);
        PactDslWithProvider dslBuilder = ConsumerPactBuilder.consumer(pact.consumer()).hasPactWith(provider);
        PactFragment pactFragment;
        try {
            pactFragment = (PactFragment) method.invoke(target, dslBuilder);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke pact method", e);
        }
        VerificationResult result = runPactTest(base, pactFragment);
        validateResult(result, pactVerification);
    }

    private Optional<PactVerification> findPactVerification(PactVerifications pactVerifications) {
        PactVerification[] pactVerificationValues = pactVerifications.value();
        Optional<PactVerification> result = Optional.empty();
        for (PactVerification verification: pactVerificationValues) {
            String[] providers = verification.value();
            if (providers.length != 1) {
                throw new IllegalArgumentException(
                  "Each @PactVerification must specify one and only provider when using @PactVerifications");
            }
            String provider = providers[0];
            if (provider.equals(this.provider)) {
                result = Optional.of(verification);
            }
        }
        return result;
    }

    private Optional<Method> findPactMethod(PactVerification pactVerification) {
        String pactFragment = pactVerification.fragment();
        for (Method method : target.getClass().getMethods()) {
            Pact pact = method.getAnnotation(Pact.class);
            if (pact != null && pact.provider().equals(provider)
                    && (pactFragment.isEmpty() || pactFragment.equals(method.getName()))) {

                validatePactSignature(method);
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    private void validatePactSignature(Method method) {
        boolean hasValidPactSignature =
                PactFragment.class.isAssignableFrom(method.getReturnType())
                        && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0].isAssignableFrom(PactDslWithProvider.class);

        if (!hasValidPactSignature) {
            throw new UnsupportedOperationException("Method " + method.getName() +
                " does not conform required method signature 'public PactFragment xxx(PactDslWithProvider builder)'");
        }
    }

    private VerificationResult runPactTest(final Statement base, PactFragment pactFragment) {
        return pactFragment.runConsumer(config, new TestRun() {
            @Override
            public void run(MockProviderConfig config) throws Throwable {
                base.evaluate();
            }
        });
    }

    private void validateResult(VerificationResult result, PactVerification pactVerification) throws Throwable {
        if (!result.equals(PACT_VERIFIED)) {
            if (result instanceof PactError) {
                throw ((PactError)result).error();
            }
            if (result instanceof UserCodeFailed) {
                throw ((UserCodeFailed<RuntimeException>)result).error();
            }
            if (result instanceof PactMismatch && !pactVerification.expectMismatch()) {
                PactMismatch mismatch = (PactMismatch) result;
                throw new PactMismatchException(mismatch);
            }
        } else if (pactVerification.expectMismatch()) {
            throw new RuntimeException("Expected a pact mismatch (PactVerification.expectMismatch is set to true)");
        }
    }

    /**
     * scan all methods for @Pact annotation and execute them, if not already initialized
     * @param fragment
     */
    protected Map<String, PactFragment> getPacts(String fragment) {
        if (fragments == null) {
            fragments = new HashMap <String, PactFragment> ();
            for (Method m: target.getClass().getMethods()) {
                if (conformsToSignature(m) && methodMatchesFragment(m, fragment)) {
                    Pact pact = m.getAnnotation(Pact.class);
                    if (StringUtils.isEmpty(pact.provider()) || provider.equals(pact.provider())) {
                        PactDslWithProvider dslBuilder = ConsumerPactBuilder.consumer(pact.consumer())
                            .hasPactWith(provider);
                        try {
                            fragments.put(provider, (PactFragment) m.invoke(target, dslBuilder));
                        } catch (Exception e) {
                            LOGGER.error("Failed to invoke pact method", e);
                            throw new RuntimeException("Failed to invoke pact method", e);
                        }
                    }
                }
            }
        }
        return fragments;
    }

    private boolean methodMatchesFragment(Method m, String fragment) {
        return StringUtils.isEmpty(fragment) || m.getName().equals(fragment);
    }

    /**
     * validates method signature as described at {@link Pact}
     */
    private boolean conformsToSignature(Method m) {
        Pact pact = m.getAnnotation(Pact.class);
        boolean conforms =
            pact != null
            && PactFragment.class.isAssignableFrom(m.getReturnType())
            && m.getParameterTypes().length == 1
            && m.getParameterTypes()[0].isAssignableFrom(PactDslWithProvider.class);

        if (!conforms && pact != null) {
            throw new UnsupportedOperationException("Method " + m.getName() +
                " does not conform required method signature 'public PactFragment xxx(PactDslWithProvider builder)'");
        }
        return conforms;
    }

}
