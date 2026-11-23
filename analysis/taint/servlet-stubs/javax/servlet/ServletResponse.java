package javax.servlet;

public interface ServletResponse {
    void setContentType(String type);

    void setCharacterEncoding(String charset);

    java.io.PrintWriter getWriter() throws java.io.IOException;

    ServletOutputStream getOutputStream() throws java.io.IOException;
}
