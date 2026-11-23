package javax.servlet;

public interface ServletRequest {
    Object getAttribute(String name);

    void setAttribute(String name, Object o);

    String getParameter(String name);

    java.util.Enumeration<String> getParameterNames();

    String[] getParameterValues(String name);

    java.util.Map<String, String[]> getParameterMap();

    String getCharacterEncoding();

    String getContentType();

    ServletInputStream getInputStream() throws java.io.IOException;

    java.io.BufferedReader getReader() throws java.io.IOException;

    String getRemoteAddr();

    String getServerName();

    RequestDispatcher getRequestDispatcher(String path);
}
