package edu.heelcode.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public record Course(String courseId, String digest, List<Source> sources) {
  public record Source(String id, String sha256, String text) {}

  public static Course ingest(String courseId, Path directory) throws IOException {
    if (!courseId.matches("[A-Za-z0-9_-]{1,80}"))
      throw new IllegalArgumentException("Invalid course ID");
    var root = directory.toRealPath();
    var sources = new ArrayList<Source>();
    try (var paths = Files.walk(root)) {
      for (var file : paths.sorted().toList()) {
        if (Files.isSymbolicLink(file))
          throw new IllegalArgumentException("Source symlinks are not supported");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
        if (!file.toString().endsWith(".md") && !file.toString().endsWith(".txt"))
          throw new IllegalArgumentException(
              "Only UTF-8 .md and .txt source files are supported: " + file.getFileName());
        if (Files.size(file) > 256_000 || sources.size() >= 64)
          throw new IllegalArgumentException("Source limits exceeded");
        var text = Files.readString(file);
        if (text.isBlank()) throw new IllegalArgumentException("Empty course source");
        sources.add(
            new Source(root.relativize(file).toString().replace('\\', '/'), hash(text), text));
      }
    }
    if (sources.isEmpty() || sources.stream().mapToInt(s -> s.text().length()).sum() > 256_000)
      throw new IllegalArgumentException("Course is empty or exceeds 256,000 characters");
    return new Course(
        courseId,
        hash(courseId + "\n" + Json.MAPPER.writeValueAsString(sources)),
        List.copyOf(sources));
  }

  static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
