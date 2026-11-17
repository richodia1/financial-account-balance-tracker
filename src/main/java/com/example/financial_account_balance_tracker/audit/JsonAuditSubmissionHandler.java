package com.example.financial_account_balance_tracker.audit;

import com.example.financial_account_balance_tracker.dto.AuditBatchSummary;
import com.example.financial_account_balance_tracker.dto.AuditSubmissionOutput;
import com.example.financial_account_balance_tracker.dto.AuditSubmissionSummary;
import com.example.financial_account_balance_tracker.model.AuditBatchingResult;
import com.example.financial_account_balance_tracker.model.AuditSubmission;
import com.example.financial_account_balance_tracker.service.AuditBatchingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.util.Objects;

/** Packs a complete window and writes one JSON document per console line. */
public final class JsonAuditSubmissionHandler implements AuditSubmissionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(JsonAuditSubmissionHandler.class);

    private final AuditBatchingService batchingService;
    private final JsonMapper jsonMapper;
    private final PrintStream output;

    public JsonAuditSubmissionHandler(AuditBatchingService batchingService, JsonMapper jsonMapper, PrintStream output) {
        this.batchingService = Objects.requireNonNull(batchingService);
        this.jsonMapper = Objects.requireNonNull(jsonMapper);
        this.output = Objects.requireNonNull(output);
    }

    @Override
    public void handle(AuditSubmission submission) {
        AuditBatchingResult result = batchingService.pack(submission);
        AuditSubmissionOutput document = new AuditSubmissionOutput(new AuditSubmissionSummary(result.batches().stream()
                .map(batch -> new AuditBatchSummary(batch.totalValueOfAllTransactions(), batch.countOfTransactions()))
                .toList()));
        output.println(jsonMapper.writeValueAsString(document));
        if (output.checkError()) {
            throw new IllegalStateException("Could not write audit submission to the console");
        }
        LOG.info("Audit submission {}: {} transactions, {} batches, lower bound {}, optimality proven: {}",
                submission.sequence(), submission.transactions().size(), result.batches().size(),
                result.batchCountLowerBound(), result.optimalityProven());
    }
}
