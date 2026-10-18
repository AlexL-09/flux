package cc.smitp.flux;

import cc.smitp.flux.storage.ProgramRepository;
import cc.smitp.flux.lang.Parser;
import cc.smitp.flux.graph.Graph;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryTest {
    @TempDir Path dir;
    @Test void savesAtomicallyAndKeepsBackupAndDraftIndependent() throws Exception {
        var repo = new ProgramRepository(dir); var meta = new ProgramRepository.Metadata();
        assertEquals(ProgramRepository.EXAMPLE,repo.loadSource());
        repo.save("version 1;",meta); repo.save("version 1; // latest",meta);
        assertEquals("version 1;",Files.readString(dir.resolve("workflows.flux.bak")));
        repo.saveDraft("broken draft"); assertEquals("broken draft",repo.loadDraft("")); assertEquals("version 1; // latest",repo.loadSource());
        try (var files = Files.list(dir)) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }
    @Test void profilesDefaultDisabledAndStayIsolated() throws Exception {
        var repo = new ProgramRepository(dir); var meta = repo.loadMetadata();
        String a = ProgramRepository.profileKey("server:a"), b = ProgramRepository.profileKey("server:b"); assertNotEquals(a,b); assertTrue(meta.enabled.isEmpty());
        meta.enabled.put(a,new HashSet<>(List.of("Farm storage"))); repo.saveMetadata(meta);
        var restored = repo.loadMetadata(); assertTrue(restored.enabled.get(a).contains("Farm storage")); assertFalse(restored.enabled.containsKey(b));
    }
    @Test void layoutSurvivesParsingWithNewNodeIds() {
        Graph g = Graph.from(Parser.parse(ProgramRepository.EXAMPLE)); var meta = new ProgramRepository.Metadata();
        g.nodes.get(g.roots.getFirst()).x = 123.5; ProgramRepository.captureLayout(g,meta);
        Graph other = Graph.from(Parser.parse(ProgramRepository.EXAMPLE)); ProgramRepository.restoreLayout(other,meta);
        assertEquals(123.5,other.nodes.get(other.roots.getFirst()).x);
    }
    @Test void malformedMetadataFailsWithoutOverwriting() throws Exception {
        Files.writeString(dir.resolve("editor.json"),"{\"version\":9}"); var repo = new ProgramRepository(dir);
        assertThrows(java.io.IOException.class,repo::loadMetadata); assertEquals("{\"version\":9}",Files.readString(dir.resolve("editor.json")));
    }
    @Test void corruptedExternalFileCannotReplaceKnownGoodBackup() throws Exception {
        var repo = new ProgramRepository(dir); var meta = new ProgramRepository.Metadata();
        repo.save("version 1;", meta, null);
        Files.writeString(dir.resolve("workflows.flux"), "corrupted external edit");
        repo.save("version 1; // replacement", meta, "version 1;");
        assertEquals("version 1;", Files.readString(dir.resolve("workflows.flux.bak")));
        repo.save("version 1; // fresh client", meta, null);
        assertEquals("version 1;", Files.readString(dir.resolve("workflows.flux.bak")));
    }
}
