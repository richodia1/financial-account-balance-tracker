package com.example.financial_account_balance_tracker.audit;

import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.model.Transaction;
import com.example.financial_account_balance_tracker.service.impl.BestFitDecreasingAuditBatchingService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JsonAuditSubmissionHandlerTests {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void printsOneValidJsonDocumentWithNumericAbsoluteTotalsInPounds() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonAuditSubmissionHandler handler = handler(new PrintStream(bytes, true, StandardCharsets.UTF_8));

        handler.handle(submission());

        List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(1);
        assertThat(jsonMapper.readTree(lines.getFirst())).isEqualTo(jsonMapper.readTree("""
                {"submission":{"batches":[
                  {"totalValueOfAllTransactions":1000000.00,"countOfTransactions":2},
                  {"totalValueOfAllTransactions":400.01,"countOfTransactions":2}
                ]}}
                """));
        assertThat(jsonMapper.readTree(lines.getFirst()).at("/submission/batches/1/totalValueOfAllTransactions").isNumber())
                .isTrue();
    }

    @Test
    void reportsConsoleWriteFailuresInsteadOfSilentlyCompletingTheSubmission() {
        PrintStream broken = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("Output unavailable");
            }
        });

        assertThatIllegalStateException().isThrownBy(() -> handler(broken).handle(submission()))
                .withMessage("Could not write audit submission to the console");
    }

    private JsonAuditSubmissionHandler handler(PrintStream output) {
        return new JsonAuditSubmissionHandler(new BestFitDecreasingAuditBatchingService(), jsonMapper, output);
    }

    private static AuditSubmission submission() {
        return new AuditSubmission(1, List.of(transaction(1, "500000"), transaction(2, "-500000"),
                transaction(3, "200.01"), transaction(4, "-200")));
    }

    private static Transaction transaction(long id, String amount) {
        return new Transaction(new UUID(0, id), new BigDecimal(amount));
    }
}
