package javax.servlet;

public interface ServletConfig {
    String getServletName();

    String getInitParameter(String name);
}
