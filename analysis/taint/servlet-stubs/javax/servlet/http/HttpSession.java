package javax.servlet.http;

public interface HttpSession {
    Object getAttribute(String name);

    void setAttribute(String name, Object value);

    Object getValue(String name);

    void putValue(String name, Object value);

    void removeAttribute(String name);

    String getId();
}
