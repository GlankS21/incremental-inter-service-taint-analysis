package javax.servlet.http;

public interface HttpServletResponse extends javax.servlet.ServletResponse {
    void addCookie(Cookie cookie);

    void setHeader(String name, String value);

    void addHeader(String name, String value);

    void setStatus(int sc);

    void sendError(int sc, String msg) throws java.io.IOException;

    void sendRedirect(String location) throws java.io.IOException;
}
