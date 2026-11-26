package javax.servlet.http;

public class Cookie implements Cloneable, java.io.Serializable {
    private final String name;
    private String value;
    private String path;
    private String domain;
    private String comment;
    private int maxAge = -1;
    private boolean secure;
    private boolean httpOnly;

    public Cookie(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String newValue) {
        value = newValue;
    }

    public void setPath(String uri) {
        path = uri;
    }

    public String getPath() {
        return path;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getDomain() {
        return domain;
    }

    public void setComment(String purpose) {
        comment = purpose;
    }

    public String getComment() {
        return comment;
    }

    public void setMaxAge(int expiry) {
        maxAge = expiry;
    }

    public void setSecure(boolean flag) {
        secure = flag;
    }

    public void setHttpOnly(boolean flag) {
        httpOnly = flag;
    }
}
