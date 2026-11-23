package javax.servlet;

public interface RequestDispatcher {
    void forward(ServletRequest request, ServletResponse response) throws ServletException, java.io.IOException;

    void include(ServletRequest request, ServletResponse response) throws ServletException, java.io.IOException;
}
