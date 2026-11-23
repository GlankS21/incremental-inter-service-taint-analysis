package javax.servlet.http;

public interface HttpServletRequest extends javax.servlet.ServletRequest {
    Cookie[] getCookies();

    String getHeader(String name);

    java.util.Enumeration<String> getHeaders(String name);

    java.util.Enumeration<String> getHeaderNames();

    String getMethod();

    String getPathInfo();

    String getContextPath();

    String getQueryString();

    String getRemoteUser();

    String getRequestURI();

    StringBuffer getRequestURL();

    String getServletPath();

    HttpSession getSession();

    HttpSession getSession(boolean create);
}
