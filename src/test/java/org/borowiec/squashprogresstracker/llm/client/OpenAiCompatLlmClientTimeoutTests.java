package org.borowiec.squashprogresstracker.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.borowiec.squashprogresstracker.llm.dto.LlmRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiCompatLlmClientTimeoutTests {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void generate_providerSlowerThanTimeout_throwsLlmExceptionWithinBudget() {
        // Body is delayed 2 s; configured timeout is 300 ms — read timeout must fire first.
        server.enqueue(new MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody("{}"));

        var props = new LlmClientProperties("k", server.url("/").toString(), "m", "", Duration.ofMillis(300));
        var restClient = new LlmClientConfig().llmRestClient(props);
        var client = new OpenAiCompatLlmClient(restClient, props, new ObjectMapper());

        var startNs = System.nanoTime();
        assertThatThrownBy(() -> client.generate(LlmRequest.ofUser("ping"))).isInstanceOf(LlmException.class);
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        // Must return well inside the 2 s body delay — confirms the configured timeout fired.
        assertThat(elapsedMs).isLessThan(1_500);
    }
}
