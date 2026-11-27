package cf.explorer.config;

public enum EnvProcessor {
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
