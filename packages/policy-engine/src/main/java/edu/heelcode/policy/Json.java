package edu.heelcode.policy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class Json {
  static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

  static <T> T read(Path path, Class<T> type) throws IOException {
    return MAPPER.readValue(Files.readString(path), type);
  }

  static void write(Path path, Object value) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    var temp = Files.createTempFile(path.toAbsolutePath().getParent(), ".policy-", ".tmp");
    try {
      Files.writeString(
          temp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
