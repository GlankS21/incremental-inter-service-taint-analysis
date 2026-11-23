package javax.servlet;

public abstract class GenericServlet implements Servlet, ServletConfig, java.io.Serializable {
    public void init(ServletConfig config) throws ServletException {
    }

    public void init() throws ServletException {
    }

    public void destroy() {
    }

    public String getServletName() {
        return null;
    }

    public String getInitParameter(String name) {
        return null;
    }

    public void log(String msg) {
    }

    public abstract void service(ServletRequest req, ServletResponse res) throws ServletException, java.io.IOException;
}
