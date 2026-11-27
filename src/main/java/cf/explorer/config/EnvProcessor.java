package cf.explorer.config;

/** Named post-processors applied to selected environment values during {@code .env} export. */
public enum EnvProcessor {
  /**
   * Strips boundary double quotes, unescapes inner quotes, and collapses whitespace. Intended for
   * {@code SPRING_APPLICATION_JSON} and similar JSON-valued keys.
   */
  JSON {
    @Override
    public String process(String rawValue) {
      if (rawValue == null) {
        return "";
      }
      String value = rawValue;
      if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
        value = value.substring(1, value.length() - 1);
      }
      value = value.replace("\\\"", "\"");
      return value.replaceAll("\\s+", " ").trim();
    }
  };

  public abstract String process(String rawValue);
}
