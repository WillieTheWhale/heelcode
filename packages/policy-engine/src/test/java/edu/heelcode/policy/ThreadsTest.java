package edu.heelcode.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The GUI's thread list is a read-only view of the engine's own durable session files. */
class ThreadsTest {
  @TempDir Path root;

  @Test
  void listsAndLoadsAdmissionOnlyAndChatThreads() throws Exception {
    var workspace = Fixtures.createDemo(root.resolve("demo"));
    assertTrue(Threads.list(workspace).isEmpty());
    Workspace.Extractor concept = (id, prompt, assignment, history) -> new Classifier.Features(id, List.of("concept"), false, false, false, "controlled");
    Workspace.Extractor solution = (id, prompt, assignment, history) -> new Classifier.Features(id, List.of("full_solution"), false, false, false, "controlled");

    var check = Workspace.check(workspace, new Workspace.Request("r1", "", "a1", "What is a zombie process?"), concept);
    Bridge.Solver solver = (dir, session, prompt, scope, run) -> new Bridge.Result("ses_thread1", "A finished child not yet reaped.", "");
    var chat = Bridge.chat(workspace, new Workspace.Request("c1", "", "a1", "Explain   fork\nversus exec."), concept, "openai/gpt-5.6-luna", solver);
    Bridge.chat(workspace, new Workspace.Request("c2", chat.sessionId(), "a1", "Write the whole shell."), solution, "openai/gpt-5.6-luna", solver);

    var threads = Threads.list(workspace);
    assertEquals(2, threads.size());
    var chatSummary = threads.stream().filter(Threads.Summary::chat).findFirst().orElseThrow();
    assertEquals("Explain fork versus exec.", chatSummary.title());
    assertEquals(2, chatSummary.turns());
    assertEquals("openai/gpt-5.6-luna", chatSummary.model());
    var admission = threads.stream().filter(t -> !t.chat()).findFirst().orElseThrow();
    assertEquals(check.sessionId(), admission.sessionId());
    assertEquals(Models.ADMISSION_ONLY, admission.model());

    var thread = Threads.load(workspace, chat.sessionId());
    assertEquals("ses_thread1", thread.engineSessionId());
    assertEquals(List.of("completed", "blocked"), thread.entries().stream().map(Threads.Entry::status).toList());
    assertEquals("A finished child not yet reaped.", thread.entries().get(0).text());
    assertEquals("deny", thread.entries().get(1).decision().action());
    assertFalse(thread.entries().get(1).inferenceAttempted());
    assertEquals("admission", Threads.load(workspace, check.sessionId()).entries().get(0).status());
  }

  @Test
  void damagedSessionFilesAreSkippedAndBadIdsRefused() throws Exception {
    var workspace = Fixtures.createDemo(root.resolve("demo"));
    Files.createDirectories(workspace.resolve(".heelcode-policy/sessions"));
    Files.writeString(workspace.resolve(".heelcode-policy/sessions/hps_" + "0".repeat(32) + ".json"), "{not json");
    assertTrue(Threads.list(workspace).isEmpty());
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Threads.load(workspace, "../escape"));
  }
}
