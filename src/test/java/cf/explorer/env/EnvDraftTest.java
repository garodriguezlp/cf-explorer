package cf.explorer.env;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.env.EnvDraft.ChangeKind;
import cf.explorer.env.EnvDraft.Entry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EnvDraftTest {
  @Test
  void parsesKeyAndKeyEqualsValueConventionPreservingEmbeddedEqualsAndSlashes() {
    var keyOnly = EnvDraft.parseNewInput("  API_URL  ");
    assertEquals("API_URL", keyOnly.key());
    assertNull(keyOnly.value());
    assertFalse(keyOnly.hasInlineValue());

    var inline = EnvDraft.parseNewInput("API_URL=https://api.example.com/v1?token=abc=123");
    assertEquals("API_URL", inline.key());
    assertEquals("https://api.example.com/v1?token=abc=123", inline.value());
    assertTrue(inline.hasInlineValue());

    var emptyVal = EnvDraft.parseNewInput("EMPTY_VAR=");
    assertEquals("EMPTY_VAR", emptyVal.key());
    assertEquals("", emptyVal.value());
    assertTrue(emptyVal.hasInlineValue());
  }

  @Test
  void validatesCloudFoundryKeySyntaxAndRejectsSystemVariables() {
    assertNull(EnvDraft.validateKey("APP_ENV"));
    assertNull(EnvDraft.validateKey("_INTERNAL_2"));
    assertNull(EnvDraft.validateKey("spring_profiles_active"));

    assertNotNull(EnvDraft.validateKey(""));
    assertNotNull(EnvDraft.validateKey("   "));
    assertNotNull(EnvDraft.validateKey("1STARTS_WITH_DIGIT"));
    assertNotNull(EnvDraft.validateKey("HAS-HYPHEN"));
    assertNotNull(EnvDraft.validateKey("VCAP_SERVICES"));
    assertNotNull(EnvDraft.validateKey("vcap_application"));
    assertNotNull(EnvDraft.validateKey("PORT"));
    assertNotNull(EnvDraft.validateKey("MEMORY_LIMIT"));
    assertNotNull(EnvDraft.validateKey("CF_INSTANCE_INDEX"));
  }

  @Test
  void stagesAdditionsUpdatesDeletionsUndoAndBuildsNullPatchMap() {
    EnvDraft draft = new EnvDraft(Map.of(
        "APP_ENV", "dev",
        "LOG_LEVEL", "INFO",
        "LEGACY_FLAG", "true"));

    assertFalse(draft.hasChanges());
    assertEquals(List.of("APP_ENV", "LEGACY_FLAG", "LOG_LEVEL"),
        draft.entries().stream().map(Entry::key).toList());

    // Stage add, update, and delete
    draft.stageSet("NEW_TOKEN", "s3cr3t");
    draft.stageSet("APP_ENV", "prod");
    draft.stageDelete("LEGACY_FLAG");

    assertTrue(draft.hasChanges());
    assertEquals(3, draft.changeCount());
    assertEquals(1, draft.addedCount());
    assertEquals(1, draft.updatedCount());
    assertEquals(1, draft.deletedCount());
    assertEquals("+1 new, ~1 updated, -1 deleted", draft.summaryBreakdown());

    assertEquals(ChangeKind.ADDED, draft.entry("NEW_TOKEN").kind());
    assertEquals(ChangeKind.UPDATED, draft.entry("APP_ENV").kind());
    assertEquals("dev", draft.entry("APP_ENV").baselineValue());
    assertEquals("prod", draft.entry("APP_ENV").effectiveValue());
    assertEquals(ChangeKind.DELETED, draft.entry("LEGACY_FLAG").kind());
    assertNull(draft.entry("LEGACY_FLAG").effectiveValue());

    Map<String, String> expectedPatch = new HashMap<>();
    expectedPatch.put("APP_ENV", "prod");
    expectedPatch.put("LEGACY_FLAG", null);
    expectedPatch.put("NEW_TOKEN", "s3cr3t");
    assertEquals(expectedPatch, draft.toPatchMap());

    // Updating back to baseline value collapses diff automatically
    draft.stageSet("APP_ENV", "dev");
    assertEquals(ChangeKind.UNCHANGED, draft.entry("APP_ENV").kind());
    assertEquals(2, draft.changeCount());

    // Deleting a newly staged variable removes it completely from the draft
    draft.stageDelete("NEW_TOKEN");
    assertNull(draft.entry("NEW_TOKEN"));
    assertEquals(1, draft.changeCount());

    // Undo restores baseline entry
    assertTrue(draft.undo("LEGACY_FLAG"));
    assertFalse(draft.hasChanges());
    assertEquals("true", draft.entry("LEGACY_FLAG").effectiveValue());
  }

  @Test
  void fuzzySearchMatchesSubstringsSubsequencesAndValuesInRankOrder() {
    EnvDraft draft = new EnvDraft(Map.of(
        "SPRING_DATASOURCE_URL", "jdbc:postgresql://db.internal:5432/orders",
        "SPRING_PROFILES_ACTIVE", "prod",
        "DB_POOL_SIZE", "20",
        "REDIS_URL", "redis://cache:6379",
        "WEBHOOK_TARGET", "https://url.internal/hook"));

    // Fuzzy subsequence "spdu" matches SPRING_DATASOURCE_URL
    assertEquals(List.of("SPRING_DATASOURCE_URL"),
        draft.filter("spdu").stream().map(Entry::key).toList());

    // Earlier key substring match (REDIS_URL) ranks first, and key matches rank ahead of value match
    assertEquals(List.of("REDIS_URL", "SPRING_DATASOURCE_URL", "WEBHOOK_TARGET"),
        draft.filter("url").stream().map(Entry::key).toList());

    // Value substring match finds port 5432
    assertEquals(List.of("SPRING_DATASOURCE_URL"),
        draft.filter("5432").stream().map(Entry::key).toList());

    // Multi-token query requires all tokens to match
    assertEquals(List.of("SPRING_DATASOURCE_URL"),
        draft.filter("spda postgres").stream().map(Entry::key).toList());
    assertTrue(draft.filter("spda redis").isEmpty());

    boolean[] matched = EnvDraft.matchedKeyChars("DB_URL", "du");
    assertArrayEquals(new boolean[] {true, false, false, true, false, false}, matched);
  }

  @Test
  void serviceLoadsDraftAndSendsPatchOnlyWhenChanged() throws Exception {
    var app = new ApplicationCatalog.App("app-1", "orders", "STARTED", "s1", "prod", "o1", "org",
        "2025-01-01", "2025-01-02");
    AtomicReference<Map<String, String>> capturedPatch = new AtomicReference<>();
    EnvEditorService service = new EnvEditorService(
        guid -> Map.of("A", "1", "B", "2"),
        (guid, patch) -> {
          capturedPatch.set(patch);
          return Map.of("A", "updated");
        });

    EnvDraft draft = service.loadDraft(app);
    assertEquals(Map.of(), service.apply(app, draft));
    assertNull(capturedPatch.get(), "No HTTP PATCH when draft has no staged changes");

    draft.stageSet("A", "updated");
    draft.stageDelete("B");
    service.apply(app, draft);
    Map<String, String> expected = new HashMap<>();
    expected.put("A", "updated");
    expected.put("B", null);
    assertEquals(expected, capturedPatch.get());
  }
}
