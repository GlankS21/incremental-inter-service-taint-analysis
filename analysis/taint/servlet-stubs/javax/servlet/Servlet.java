package javax.servlet;

/** Analysis-only stub of the Servlet API (signatures, no behaviour): see analysis/README.md, Servlet applications. */
public interface Servlet {
    void init(ServletConfig config) throws ServletException;

    void service(ServletRequest req, ServletResponse res) throws ServletException, java.io.IOException;

    void destroy();
}
