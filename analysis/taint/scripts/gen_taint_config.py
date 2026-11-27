"""Generates the base taint config of a service (taint-<service>.yml, Tai-e format) from the channel
model and the service bytecode, replacing the hand-written config/taint-<x>.yml.

  sources   = parameters of the service's externally reachable endpoints (exposed through the gateway;
              every endpoint when the model has no gateway) that a client controls: not primitives,
              enums or framework-built values (Pageable, Principal, ...); plus the payload parameter of
              every AMQP listener (marked "# AMQP listener": mk_field_config.py replaces it by the
              event field sources)
  sinks     = outgoing channel ends from the model (OpenFeign client methods, AmqpTemplate sends when
              spring-amqp is packaged, the outbox enqueue as channel-extractor identifies publishers)
              + the selected sink catalogues (cwe-89: JPA, JdbcTemplate, MyBatis ${} mappers;
              cwe-117: slf4j)
  transfers = accessors of the discovered request DTO packages and event packages, event constructors
              (services that publish), and a JDK catalogue (boxing, collections, streams, strings)

Field-level parts (DTO/event/entity field sources, database channel) are added afterwards by the
mk_*.py scripts from the discovery written next to the config (vkr_prepare.py); the overrides file is
merged last. Signatures are read with javap (JDK of toolchain.java-home) from the built classes.

usage: gen_taint_config.py [--config vkr-taint.yml] --model channels.json [--services a,b]
                           --out-dir DIR          -> DIR/taint-<service>.yml, DIR/discovery-<service>.json
"""
import argparse
import io
import json
import os
import re
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vkr_config  # noqa: E402
from vkr_yaml import dump_entry  # noqa: E402

PRIMITIVES = {'boolean', 'byte', 'char', 'short', 'int', 'long', 'float', 'double', 'void'}
# built by the framework from the request, not text a client chooses
FRAMEWORK_TYPES = {
    'org.springframework.data.domain.Pageable', 'org.springframework.data.domain.Sort',
    'java.security.Principal', 'org.springframework.security.core.Authentication',
    'jakarta.servlet.http.HttpServletRequest', 'jakarta.servlet.http.HttpServletResponse',
    'javax.servlet.http.HttpServletRequest', 'javax.servlet.http.HttpServletResponse',
    'org.springframework.web.server.ServerWebExchange', 'org.springframework.validation.BindingResult',
    'java.util.Locale',
}
# the outbox signature channel-extractor uses by default (AmqpExtractor.DEFAULT_OUTBOX_SIGNATURES):
# a call <receiver whose type ends with OutboxService>.enqueue(exchange, routingKey, payload, ...)
OUTBOX_SIGNATURES = [('OutboxService', 'enqueue', 2)]
ENTITY_ANNOTATIONS = (b'Ljakarta/persistence/Entity;', b'Ljavax/persistence/Entity;')
# Kotlin request handlers: channel-extractor parses Java sources only, so a controller written in
# Kotlin has no endpoint in the channel model; its handlers are read from the bytecode instead
KOTLIN_METADATA = b'Lkotlin/Metadata;'
WEB_ANNOTATION = 'org.springframework.web.bind.annotation.'
MAPPING_ANNOTATIONS = {WEB_ANNOTATION + a for a in (
    'RequestMapping', 'GetMapping', 'PostMapping', 'PutMapping', 'PatchMapping', 'DeleteMapping')}
CONTROLLER_ANNOTATIONS = {WEB_ANNOTATION + 'RestController', 'org.springframework.stereotype.Controller'}

CATALOGUES = {
    'cwe-89': [
        ('SQL injection', [
            {'method': '<jakarta.persistence.EntityManager: jakarta.persistence.Query createNativeQuery(java.lang.String)>', 'index': 0},
            {'method': '<jakarta.persistence.EntityManager: jakarta.persistence.Query createQuery(java.lang.String)>', 'index': 0},
            # Spring Boot 2 and older: JPA under javax (a catalogue entry whose class the service does
            # not package is dropped, see Service.has_class)
            {'method': '<javax.persistence.EntityManager: javax.persistence.Query createNativeQuery(java.lang.String)>', 'index': 0},
            {'method': '<javax.persistence.EntityManager: javax.persistence.Query createQuery(java.lang.String)>', 'index': 0},
            {'method': '<org.springframework.jdbc.core.JdbcTemplate: * query*(java.lang.String,*{0+})>', 'index': 0},
            {'method': '<org.springframework.jdbc.core.JdbcTemplate: int update(java.lang.String,*{0+})>', 'index': 0},
            {'method': '<org.springframework.jdbc.core.JdbcTemplate: void execute(java.lang.String)>', 'index': 0},
            {'method': '<org.springframework.jdbc.core.JdbcTemplate: int[] batchUpdate(java.lang.String[])>', 'index': '0[*]'},
        ]),
        ('SQL injection: plain JDBC (the statement text; a PreparedStatement parameter is bound, not a sink)', [
            {'method': '<java.sql.Statement: * execute*(java.lang.String,*{0+})>', 'index': 0},
            {'method': '<java.sql.Statement: void addBatch(java.lang.String)>', 'index': 0},
            {'method': '<java.sql.Connection: * prepare*(java.lang.String,*{0+})>', 'index': 0},
        ]),
    ],
    'cwe-117': [
        ('log injection (CWE-117): the message, also when built by concatenation, and the arguments', [
            {'method': '<org.slf4j.Logger: void *(java.lang.String)>', 'index': 0},
            {'method': '<org.slf4j.Logger: void *(java.lang.String,java.lang.Object)>', 'index': 0},
            {'method': '<org.slf4j.Logger: void *(java.lang.String,java.lang.Object,java.lang.Object)>', 'index': 0},
            {'method': '<org.slf4j.Logger: void *(java.lang.String,java.lang.Object)>', 'index': 1},
            {'method': '<org.slf4j.Logger: void *(java.lang.String,java.lang.Object,java.lang.Object)>', 'index': 1},
            {'method': '<org.slf4j.Logger: void *(java.lang.String,java.lang.Object,java.lang.Object)>', 'index': 2},
        ]),
        ('varargs: wildcard method names cannot be combined with array types in Tai-e patterns', [
            {'method': f'<org.slf4j.Logger: void {m}(java.lang.String,java.lang.Object[])>', 'index': i}
            for m in ('info', 'warn', 'error', 'debug') for i in (0, '1[*]')
        ]),
    ],
    'cwe-918': [
        ('server-side request forgery (CWE-918): a request-controlled URL is opened', [
            {'method': '<java.net.URL: java.net.URLConnection openConnection()>', 'index': 'base'},
            {'method': '<java.net.URL: java.net.URLConnection openConnection(java.net.Proxy)>', 'index': 'base'},
            {'method': '<java.net.URL: java.io.InputStream openStream()>', 'index': 'base'},
            {'method': '<java.net.http.HttpRequest: java.net.http.HttpRequest$Builder newBuilder(java.net.URI)>', 'index': 0},
            {'method': '<java.net.http.HttpRequest$Builder: java.net.http.HttpRequest$Builder uri(java.net.URI)>', 'index': 0},
        ]),
    ],
    'cwe-1336': [
        ('template injection (CWE-1336): request text is compiled as a template', [
            {'method': '<freemarker.template.Template: void <init>(java.lang.String,java.io.Reader,freemarker.template.Configuration)>', 'index': 1},
            {'method': '<freemarker.template.Template: void <init>(java.lang.String,java.lang.String,freemarker.template.Configuration)>', 'index': 1},
            {'method': '<org.thymeleaf.TemplateEngine: java.lang.String process(java.lang.String,org.thymeleaf.context.IContext)>', 'index': 0},
            {'method': '<org.thymeleaf.ITemplateEngine: java.lang.String process(java.lang.String,org.thymeleaf.context.IContext)>', 'index': 0},
            {'method': '<org.apache.velocity.app.Velocity: boolean evaluate(org.apache.velocity.context.Context,java.io.Writer,java.lang.String,java.lang.String)>', 'index': 3},
        ]),
    ],
    # CWE-79: the response body of a web handler is the sink; vkr.plugin.ResponseBodySink checks it
    # after the analysis (a return value is not a call site), so the catalogue has no call sinks
    # (a servlet writes its body through the response writer: SERVLET_SINKS)
    'cwe-79': [],
    'cwe-78': [
        ('OS command injection (CWE-78): the command line, its arguments or its environment', [
            {'method': '<java.lang.Runtime: java.lang.Process exec(java.lang.String)>', 'index': 0},
            {'method': '<java.lang.Runtime: java.lang.Process exec(java.lang.String[])>', 'index': '0[*]'},
            {'method': '<java.lang.Runtime: java.lang.Process exec(java.lang.String,java.lang.String[])>', 'index': 0},
            {'method': '<java.lang.Runtime: java.lang.Process exec(java.lang.String,java.lang.String[])>', 'index': '1[*]'},
            {'method': '<java.lang.Runtime: java.lang.Process exec(java.lang.String[],java.lang.String[])>', 'index': '0[*]'},
            {'method': '<java.lang.Runtime: java.lang.Process exec(java.lang.String[],java.lang.String[])>', 'index': '1[*]'},
            {'method': '<java.lang.Runtime: java.lang.Process exec(java.lang.String,java.lang.String[],java.io.File)>', 'index': 0},
            {'method': '<java.lang.Runtime: java.lang.Process exec(java.lang.String,java.lang.String[],java.io.File)>', 'index': '1[*]'},
            {'method': '<java.lang.Runtime: java.lang.Process exec(java.lang.String[],java.lang.String[],java.io.File)>', 'index': '0[*]'},
            {'method': '<java.lang.Runtime: java.lang.Process exec(java.lang.String[],java.lang.String[],java.io.File)>', 'index': '1[*]'},
            {'method': '<java.lang.ProcessBuilder: void <init>(java.lang.String[])>', 'index': '0[*]'},
            {'method': '<java.lang.ProcessBuilder: void <init>(java.util.List)>', 'index': 0},
            {'method': '<java.lang.ProcessBuilder: java.lang.ProcessBuilder command(java.lang.String[])>', 'index': '0[*]'},
            {'method': '<java.lang.ProcessBuilder: java.lang.ProcessBuilder command(java.util.List)>', 'index': 0},
        ]),
    ],
    'cwe-22': [
        ('path traversal (CWE-22): request text names the file opened', [
            {'method': '<java.io.File: void <init>(java.lang.String)>', 'index': 0},
            {'method': '<java.io.File: void <init>(java.lang.String,java.lang.String)>', 'index': 0},
            {'method': '<java.io.File: void <init>(java.lang.String,java.lang.String)>', 'index': 1},
            {'method': '<java.io.File: void <init>(java.io.File,java.lang.String)>', 'index': 1},
            {'method': '<java.io.File: void <init>(java.net.URI)>', 'index': 0},
            {'method': '<java.io.FileInputStream: void <init>(java.lang.String)>', 'index': 0},
            {'method': '<java.io.FileOutputStream: void <init>(java.lang.String)>', 'index': 0},
            {'method': '<java.io.FileOutputStream: void <init>(java.lang.String,boolean)>', 'index': 0},
            {'method': '<java.io.FileReader: void <init>(java.lang.String)>', 'index': 0},
            {'method': '<java.io.FileWriter: void <init>(java.lang.String)>', 'index': 0},
            {'method': '<java.io.FileWriter: void <init>(java.lang.String,boolean)>', 'index': 0},
            {'method': '<java.io.RandomAccessFile: void <init>(java.lang.String,java.lang.String)>', 'index': 0},
            {'method': '<java.nio.file.Paths: java.nio.file.Path get(java.lang.String,java.lang.String[])>', 'index': 0},
            {'method': '<java.nio.file.Paths: java.nio.file.Path get(java.lang.String,java.lang.String[])>', 'index': '1[*]'},
            {'method': '<java.nio.file.Paths: java.nio.file.Path get(java.net.URI)>', 'index': 0},
        ]),
    ],
}
# a URI built from components is tainted by any of them: the path for a file (CWE-22), the host
# for a request (CWE-918); new URI("file", null, dir + name, null, null) then new File(uri)
URI_COMPONENTS = [
    (f'<java.net.URI: void <init>({sig})>', i, 'base')
    for sig, idx in [
        ('java.lang.String,java.lang.String,java.lang.String', (0, 1, 2)),
        ('java.lang.String,java.lang.String,java.lang.String,java.lang.String', (0, 1, 2, 3)),
        ('java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String', (0, 1, 2, 3, 4)),
        ('java.lang.String,java.lang.String,java.lang.String,int,java.lang.String,java.lang.String,java.lang.String',
         (0, 1, 2, 4, 5, 6)),
    ]
    for i in idx
]
# transfers that only a catalogue needs (the URL or template text derived from the request text)
CATALOGUE_TRANSFERS = {
    'cwe-918': [
        ('<java.net.URI: java.net.URI create(java.lang.String)>', 0, 'result'),
        ('<java.net.URI: void <init>(java.lang.String)>', 0, 'base'),
        ('<java.net.URI: java.net.URL toURL()>', 'base', 'result'),
        ('<java.net.URL: void <init>(java.lang.String)>', 0, 'base'),
        ('<java.net.URL: void <init>(java.net.URL,java.lang.String)>', 1, 'base'),
        *URI_COMPONENTS,
    ],
    'cwe-1336': [
        ('<java.io.StringReader: void <init>(java.lang.String)>', 0, 'base'),
    ],
    'cwe-22': [
        ('<java.net.URI: void <init>(java.lang.String)>', 0, 'base'),
        ('<java.net.URI: java.net.URI create(java.lang.String)>', 0, 'result'),
        *URI_COMPONENTS,
    ],
}
# HTML encoders end a flow (a Tai-e sanitizer is global: encoded text is clean for every sink)
CATALOGUE_SANITIZERS = {
    'cwe-79': [
        {'kind': 'param', 'method': '<org.springframework.web.util.HtmlUtils: java.lang.String htmlEscape(java.lang.String)>', 'index': 0},
        {'kind': 'param', 'method': '<org.springframework.web.util.HtmlUtils: java.lang.String htmlEscape(java.lang.String,java.lang.String)>', 'index': 0},
        {'kind': 'param', 'method': '<org.apache.commons.text.StringEscapeUtils: java.lang.String escapeHtml4(java.lang.String)>', 'index': 0},
        {'kind': 'param', 'method': '<org.apache.commons.lang3.StringEscapeUtils: java.lang.String escapeHtml4(java.lang.String)>', 'index': 0},
        {'kind': 'param', 'method': '<org.owasp.encoder.Encode: java.lang.String forHtml(java.lang.String)>', 'index': 0},
        {'kind': 'param', 'method': '<org.owasp.encoder.Encode: java.lang.String forHtmlContent(java.lang.String)>', 'index': 0},
        {'kind': 'param', 'method': '<org.apache.commons.lang.StringEscapeUtils: java.lang.String escapeHtml(java.lang.String)>', 'index': 0},
    ],
}

# A servlet application (an HttpServlet subclass, no Spring request mapping) reads the request through
# getters: their results are the sources ({ns} = javax or jakarta). An array result is typed by its
# elements: vkr.plugin.ServletEntryPoints lets an array load from a tainted array read the taint.
SERVLET_SOURCES = [
    ('{ns}.servlet.ServletRequest', 'java.lang.String getParameter(java.lang.String)', None),
    ('{ns}.servlet.ServletRequest', 'java.lang.String[] getParameterValues(java.lang.String)', 'java.lang.String'),
    ('{ns}.servlet.ServletRequest', 'java.util.Map getParameterMap()', None),
    ('{ns}.servlet.ServletRequest', 'java.util.Enumeration getParameterNames()', None),
    ('{ns}.servlet.http.HttpServletRequest', 'java.lang.String getHeader(java.lang.String)', None),
    ('{ns}.servlet.http.HttpServletRequest', 'java.util.Enumeration getHeaders(java.lang.String)', None),
    ('{ns}.servlet.http.HttpServletRequest', 'java.util.Enumeration getHeaderNames()', None),
    ('{ns}.servlet.http.HttpServletRequest', 'java.lang.String getQueryString()', None),
    # the request line and the authenticated user name: text the client chooses
    ('{ns}.servlet.http.HttpServletRequest', 'java.lang.String getRequestURI()', None),
    ('{ns}.servlet.http.HttpServletRequest', 'java.lang.StringBuffer getRequestURL()', None),
    ('{ns}.servlet.http.HttpServletRequest', 'java.lang.String getPathInfo()', None),
    ('{ns}.servlet.http.HttpServletRequest', 'java.lang.String getPathTranslated()', None),
    ('{ns}.servlet.http.HttpServletRequest', 'java.lang.String getRemoteUser()', None),
    # the request body
    ('{ns}.servlet.ServletRequest', 'java.io.BufferedReader getReader()', None),
    ('{ns}.servlet.ServletRequest', '{ns}.servlet.ServletInputStream getInputStream()', None),
    # cookies of the request: every attribute the client sends
    ('{ns}.servlet.http.Cookie', 'java.lang.String getValue()', None),
    ('{ns}.servlet.http.Cookie', 'java.lang.String getName()', None),
    ('{ns}.servlet.http.Cookie', 'java.lang.String getComment()', None),
    ('{ns}.servlet.http.Cookie', 'java.lang.String getDomain()', None),
    ('{ns}.servlet.http.Cookie', 'java.lang.String getPath()', None),
]
# Not sources: values the container restricts to a few constants or that the deployment sets
# (getScheme, getProtocol, getMethod, getAuthType, getServletPath, getContextPath, getInitParameter).
# the text a servlet writes to its response writer (CWE-79)
SERVLET_SINKS = [
    {'method': f'<java.io.PrintWriter: {sig}>', 'index': i} for sig, i in [
        ('void print(java.lang.String)', 0), ('void println(java.lang.String)', 0),
        ('void print(java.lang.Object)', 0), ('void println(java.lang.Object)', 0),
        ('void print(char[])', 0), ('void println(char[])', 0),
        ('void write(java.lang.String)', 0), ('void write(java.lang.String,int,int)', 0),
        ('void write(char[])', 0), ('void write(char[],int,int)', 0),
        ('java.io.PrintWriter format(java.lang.String,java.lang.Object[])', 0),
        ('java.io.PrintWriter format(java.lang.String,java.lang.Object[])', '1[*]'),
        ('java.io.PrintWriter printf(java.lang.String,java.lang.Object[])', 0),
        ('java.io.PrintWriter printf(java.lang.String,java.lang.Object[])', '1[*]'),
        ('java.io.PrintWriter format(java.util.Locale,java.lang.String,java.lang.Object[])', 1),
        ('java.io.PrintWriter format(java.util.Locale,java.lang.String,java.lang.Object[])', '2[*]'),
        ('java.io.PrintWriter printf(java.util.Locale,java.lang.String,java.lang.Object[])', 1),
        ('java.io.PrintWriter printf(java.util.Locale,java.lang.String,java.lang.Object[])', '2[*]'),
        ('java.io.PrintWriter append(java.lang.CharSequence)', 0),
    ]]
# Servlet code works on the raw request strings with JDK (and commons-codec) APIs that the Spring
# services do not need: binding gives them typed values. Added only for a servlet application, so the
# configurations of the Spring testbeds stay as measured.
SERVLET_TRANSFERS = [
    # the element type is given (4th item): the code casts what nextElement()/get() return, and Tai-e
    # drops a taint object of type Object at a cast to String / String[]
    ('request getters returning containers', [
        ('<java.util.Enumeration^: java.lang.Object nextElement()>', 'base', 'result', 'java.lang.String'),
        ('<java.util.Map^: java.lang.Object get(java.lang.Object)>', 'base', 'result', 'java.lang.String[]'),
        ('<java.util.Map^: java.util.Collection values()>', 'base', 'result'),
        ('<java.util.Map^: java.util.Set entrySet()>', 'base', 'result'),
        # no ^ with an array type (Tai-e cannot combine a subtype pattern with one): the concrete classes
        ('<java.util.ArrayList: java.lang.Object[] toArray()>', 'base', 'result'),
        ('<java.util.LinkedList: java.lang.Object[] toArray()>', 'base', 'result'),
    ]),
    ('strings, bytes and characters', [
        ('<java.lang.String: java.lang.String[] split(java.lang.String)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String[] split(java.lang.String,int)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String substring(int)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String replace(char,char)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String replace(java.lang.CharSequence,java.lang.CharSequence)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String replace(java.lang.CharSequence,java.lang.CharSequence)>', 1, 'result'),
        ('<java.lang.String: java.lang.String replaceAll(java.lang.String,java.lang.String)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String replaceFirst(java.lang.String,java.lang.String)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String concat(java.lang.String)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String concat(java.lang.String)>', 0, 'result'),
        ('<java.lang.String: java.lang.String toLowerCase()>', 'base', 'result'),
        ('<java.lang.String: java.lang.String toUpperCase()>', 'base', 'result'),
        ('<java.lang.String: java.lang.String intern()>', 'base', 'result'),
        ('<java.lang.String: java.lang.String toString()>', 'base', 'result'),
        ('<java.lang.String: char[] toCharArray()>', 'base', 'result'),
        ('<java.lang.String: byte[] getBytes()>', 'base', 'result'),
        ('<java.lang.String: byte[] getBytes(java.lang.String)>', 'base', 'result'),
        ('<java.lang.String: byte[] getBytes(java.nio.charset.Charset)>', 'base', 'result'),
        ('<java.lang.String: void <init>(java.lang.String)>', 0, 'base'),
        ('<java.lang.String: void <init>(char[])>', 0, 'base'),
        ('<java.lang.String: void <init>(byte[])>', 0, 'base'),
        ('<java.lang.String: void <init>(byte[],java.lang.String)>', 0, 'base'),
        ('<java.lang.String: void <init>(byte[],java.nio.charset.Charset)>', 0, 'base'),
        ('<java.lang.String: void <init>(char[],int,int)>', 0, 'base'),
        ('<java.lang.String: java.lang.String valueOf(char[])>', 0, 'result'),
        ('<java.lang.String: java.lang.String valueOf(char[],int,int)>', 0, 'result'),
        ('<java.lang.String: java.lang.String copyValueOf(char[])>', 0, 'result'),
        ('<java.lang.String: java.lang.String join(java.lang.CharSequence,java.lang.Iterable)>', 1, 'result'),
        ('<java.lang.StringBuilder: void <init>(java.lang.String)>', 0, 'base'),
        ('<java.lang.StringBuilder: void <init>(java.lang.CharSequence)>', 0, 'base'),
        ('<java.lang.StringBuffer: void <init>(java.lang.String)>', 0, 'base'),
        # a query string parsed by hand: new StringTokenizer(request.getQueryString(), "&").nextToken()
        ('<java.util.StringTokenizer: void <init>(java.lang.String)>', 0, 'base'),
        ('<java.util.StringTokenizer: void <init>(java.lang.String,java.lang.String)>', 0, 'base'),
        ('<java.util.StringTokenizer: void <init>(java.lang.String,java.lang.String,boolean)>', 0, 'base'),
        ('<java.util.StringTokenizer: java.lang.String nextToken()>', 'base', 'result'),
        ('<java.util.StringTokenizer: java.lang.String nextToken(java.lang.String)>', 'base', 'result'),
        ('<java.util.StringTokenizer: java.lang.Object nextElement()>', 'base', 'result', 'java.lang.String'),
        ('<java.lang.AbstractStringBuilder^: * insert(int,java.lang.String)>', 1, 'base'),
        ('<java.lang.StringBuilder: java.lang.StringBuilder append(char[])>', 0, 'base'),
        ('<java.lang.StringBuffer: java.lang.StringBuffer append(char[])>', 0, 'base'),
        ('<java.lang.AbstractStringBuilder^: * append(java.lang.CharSequence)>', 0, 'base'),
        ('<java.lang.AbstractStringBuilder^: * replace(int,int,java.lang.String)>', 2, 'base'),
        ('<java.lang.AbstractStringBuilder^: * replace(int,int,java.lang.String)>', 'base', 'result'),
        ('<java.lang.AbstractStringBuilder^: * substring(int)>', 'base', 'result'),
        ('<java.lang.AbstractStringBuilder^: * substring(int,int)>', 'base', 'result'),
        ('<java.lang.AbstractStringBuilder^: * reverse()>', 'base', 'result'),
        ('<java.lang.AbstractStringBuilder^: * append(java.lang.Object^)>', 'base', 'result'),
        ('<java.lang.AbstractStringBuilder^: * append(java.lang.String)>', 'base', 'result'),
    ]),
    ('readers of the request body', [
        ('<java.io.InputStreamReader: void <init>(java.io.InputStream)>', 0, 'base'),
        ('<java.io.InputStreamReader: void <init>(java.io.InputStream,java.lang.String)>', 0, 'base'),
        ('<java.io.InputStreamReader: void <init>(java.io.InputStream,java.nio.charset.Charset)>', 0, 'base'),
        ('<java.io.BufferedReader: void <init>(java.io.Reader)>', 0, 'base'),
        ('<java.io.BufferedReader: java.lang.String readLine()>', 'base', 'result'),
    ]),
    ('decoders and encoders that keep the attacker text', [
        ('<java.net.URLDecoder: java.lang.String decode(java.lang.String,java.lang.String)>', 0, 'result'),
        ('<java.net.URLDecoder: java.lang.String decode(java.lang.String)>', 0, 'result'),
        ('<java.util.Base64$Decoder: byte[] decode(byte[])>', 0, 'result'),
        ('<java.util.Base64$Decoder: byte[] decode(java.lang.String)>', 0, 'result'),
        ('<java.util.Base64$Encoder: byte[] encode(byte[])>', 0, 'result'),
        ('<java.util.Base64$Encoder: java.lang.String encodeToString(byte[])>', 0, 'result'),
        ('<org.apache.commons.codec.binary.Base64: byte[] decodeBase64(byte[])>', 0, 'result'),
        ('<org.apache.commons.codec.binary.Base64: byte[] decodeBase64(java.lang.String)>', 0, 'result'),
        ('<org.apache.commons.codec.binary.Base64: byte[] encodeBase64(byte[])>', 0, 'result'),
    ]),
]

# RestTemplate calls of the channel model (clientKind REST_TEMPLATE): the URL (path variables and
# query parameters concatenated into it) and the request body are the outgoing channel end.
# summarize.py maps a flow into one of these calls to the client recorded at that call site.
RT = 'org.springframework.web.client.RestTemplate'
RE = 'org.springframework.http.ResponseEntity'
REST_TEMPLATE_SINKS = [
    (f'<{RT}: {ret} {m}({params})>', idx)
    for ret, m, params, idxs in [
        (RE, 'exchange', 'java.lang.String,org.springframework.http.HttpMethod,org.springframework.http.HttpEntity,java.lang.Class,java.lang.Object[]', (0, 2)),
        (RE, 'exchange', 'java.lang.String,org.springframework.http.HttpMethod,org.springframework.http.HttpEntity,org.springframework.core.ParameterizedTypeReference,java.lang.Object[]', (0, 2)),
        (RE, 'exchange', 'java.lang.String,org.springframework.http.HttpMethod,org.springframework.http.HttpEntity,java.lang.Class,java.util.Map', (0, 2)),
        ('java.lang.Object', 'postForObject', 'java.lang.String,java.lang.Object,java.lang.Class,java.lang.Object[]', (0, 1)),
        (RE, 'postForEntity', 'java.lang.String,java.lang.Object,java.lang.Class,java.lang.Object[]', (0, 1)),
        ('java.lang.Object', 'getForObject', 'java.lang.String,java.lang.Class,java.lang.Object[]', (0,)),
        (RE, 'getForEntity', 'java.lang.String,java.lang.Class,java.lang.Object[]', (0,)),
        ('void', 'put', 'java.lang.String,java.lang.Object,java.lang.Object[]', (0, 1)),
        ('void', 'delete', 'java.lang.String,java.lang.Object[]', (0,)),
    ] for idx in idxs]
REST_TEMPLATE_TRANSFERS = [
    ('<org.springframework.http.HttpEntity: void <init>(java.lang.Object)>', 0, 'base'),
    ('<org.springframework.http.HttpEntity: void <init>(java.lang.Object,org.springframework.util.MultiValueMap)>', 0, 'base'),
]

JDK_TRANSFERS = [
    ('boxing', [
        ('<java.lang.Long: java.lang.Long valueOf(long)>', 0, 'result'),
        ('<java.lang.Long: long longValue()>', 'base', 'result'),
    ]),
    ('collections built from a tainted collection/element (factory methods only)', [
        ('<java.util.List: java.util.List of(java.lang.Object)>', 0, 'result'),
        ('<java.util.List: java.util.List copyOf(java.util.Collection)>', 0, 'result'),
        ('<java.util.Set: java.util.Set copyOf(java.util.Collection)>', 0, 'result'),
        ('<java.util.Collection^: java.util.stream.Stream stream()>', 'base', 'result'),
        ('<java.util.stream.Stream^: java.util.stream.Stream map(java.util.function.Function)>', 'base', 'result'),
        ('<java.util.stream.Stream^: java.util.stream.Stream filter(java.util.function.Predicate)>', 'base', 'result'),
        ('<java.util.stream.Stream^: java.util.List toList()>', 'base', 'result'),
        ('<java.util.stream.Stream^: java.lang.Object collect(java.util.stream.Collector)>', 'base', 'result'),
    ]),
    ('strings', [
        ('<java.lang.String: java.lang.String valueOf(java.lang.Object)>', 0, 'result'),
        ('<java.lang.String: java.lang.String format(java.lang.String,java.lang.Object[])>', '1[*]', 'result'),
        ('<java.lang.String: java.lang.String substring(int,int)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String trim()>', 'base', 'result'),
        ('<java.lang.String: java.lang.String strip()>', 'base', 'result'),
        ('<java.lang.String: java.lang.String toUpperCase(java.util.Locale)>', 'base', 'result'),
        ('<java.lang.String: java.lang.String toLowerCase(java.util.Locale)>', 'base', 'result'),
        ('<java.lang.AbstractStringBuilder^: * append(java.lang.Object^)>', 0, 'base'),
        ('<java.lang.AbstractStringBuilder^: * append(java.lang.String)>', 0, 'base'),
        ('<java.lang.AbstractStringBuilder^: * toString()>', 'base', 'result'),
    ]),
    ('maps keyed by request values', [
        ('<java.util.Map^: java.lang.Object put(java.lang.Object,java.lang.Object)>', 0, 'base'),
        ('<java.util.Map^: java.lang.Object merge(java.lang.Object,java.lang.Object,java.util.function.BiFunction)>', 0, 'base'),
        ('<java.util.Map^: java.util.Set keySet()>', 'base', 'result'),
    ]),
    ('iterating a tainted request list', [
        ('<java.lang.Iterable^: java.util.Iterator iterator()>', 'base', 'result'),
        ('<java.util.Iterator^: java.lang.Object next()>', 'base', 'result'),
        ('<java.util.List^: java.lang.Object get(int)>', 'base', 'result'),
    ]),
    ('i18n messages embed their arguments; no ^: Tai-e cannot combine a subtype pattern with an array '
     'parameter type', [
        ('<org.springframework.context.MessageSource: java.lang.String getMessage(java.lang.String,java.lang.Object[],java.util.Locale)>', '1[*]', 'result'),
        ('<java.util.Map$Entry^: java.lang.Object getKey()>', 'base', 'result'),
        ('<java.util.Map$Entry^: java.lang.Object getValue()>', 'base', 'result'),
    ]),
]


# ------------------------------------------------------------------ bytecode (javap)

_DESC = re.compile(r'\[*(?:[BCDFIJSZV]|L[^;]+;)')
_PRIM = {'B': 'byte', 'C': 'char', 'D': 'double', 'F': 'float', 'I': 'int', 'J': 'long', 'S': 'short',
         'Z': 'boolean', 'V': 'void'}


def desc_type(d):
    dims = len(d) - len(d.lstrip('['))
    d = d[dims:]
    t = _PRIM[d] if len(d) == 1 else d[1:-1].replace('/', '.')
    return t + '[]' * dims


def parse_descriptor(desc):
    m = re.fullmatch(r'\((.*)\)(.+)', desc)
    return [desc_type(x) for x in _DESC.findall(m.group(1))], desc_type(m.group(2))


class ClassInfo:
    def __init__(self, name, header):
        self.name = name
        self.header = header
        self.is_enum = ' extends java.lang.Enum<' in header
        self.is_record = ' extends java.lang.Record' in header
        self.is_interface = re.search(r'\binterface\b', header.split(name)[0]) is not None
        self.methods = []  # (name, [param types], return type, static)
        self.fields = []   # (type, name, modifiers)

    def find(self, name, argc=None):
        return [m for m in self.methods if m[0] == name and (argc is None or len(m[1]) == argc)]


def tai_sig(cls, m):
    return f'<{cls}: {m[2]} {m[0]}({",".join(m[1])})>'


def javap(java_home, classpath, classes, chunk=150):
    """{class name: ClassInfo} for the classes javap finds; one JVM per chunk of classes."""
    exe = os.path.join(java_home, 'bin', 'javap')
    out = {}
    classes = sorted(set(classes))
    for i in range(0, len(classes), chunk):
        r = subprocess.run([exe, '-p', '-s', '-cp', os.pathsep.join(classpath), *classes[i:i + chunk]],
                           capture_output=True, text=True, encoding='utf-8', errors='replace')
        out.update(parse_javap(r.stdout))
    return out


def parse_javap(text):
    result, cur, pending = {}, None, None
    for line in text.splitlines():
        if not line.startswith(' '):
            if line.startswith('Compiled from') or not line.strip():
                continue
            if line.startswith('}'):
                cur = None
                continue
            m = re.search(r'\b(?:class|interface|enum|record)\s+([\w.$]+)', line)
            if m and line.rstrip().endswith('{'):
                cur = ClassInfo(m.group(1), line)
                result[cur.name] = cur
            continue
        if cur is None:
            continue
        s = line.strip()
        if s.startswith('descriptor:') and pending is not None:
            desc = s.split(':', 1)[1].strip()
            decl = pending
            pending = None
            if '(' in desc:
                params, ret = parse_descriptor(desc)
                head = decl.split('(', 1)[0].split()
                name = head[-1] if head else ''
                if name == cur.name:
                    name, ret = '<init>', 'void'
                if name and not decl.startswith('static {}'):
                    cur.methods.append((name, params, ret, 'static' in head[:-1]))
            else:
                parts = decl.rstrip(';').split()
                cur.fields.append((desc_type(desc), parts[-1], parts[:-2]))
            continue
        if line.startswith('  ') and not line.startswith('    '):
            pending = s
    return result


_ANNOTATION = re.compile(r'^([a-z][\w$]*(?:\.[\w$]+)*\.[A-Z][\w$]*)(?:\(|$)')
_SECTION = re.compile(r'^[A-Z]\w*:$')


def parse_handlers(text):
    """javap -p -v output -> [(class, (name, params, return, static))] of the methods carrying a request
    mapping annotation, in classes annotated as controllers."""
    out, cur, method, mode, class_ann, handlers = [], None, None, None, set(), []

    def close():
        if cur and class_ann & CONTROLLER_ANNOTATIONS:
            out.extend((cur, m) for m in handlers)

    pending = None
    for line in text.splitlines():
        if not line.startswith(' '):
            m = re.search(r'\b(?:class|interface)\s+([\w.$]+)', line)
            if m and not line.startswith('Compiled from'):
                close()
                cur, method, mode, class_ann, handlers = m.group(1), None, None, set(), []
            elif line.startswith('}'):
                method, mode = None, None
            elif line.strip() == 'RuntimeVisibleAnnotations:':
                mode = 'class'
            elif _SECTION.match(line.strip()):
                mode = None
            continue
        st = line.strip()
        if line.startswith('  ') and not line.startswith('   ') and '(' in st and st.endswith(';'):
            pending, method, mode = st, None, None
            continue
        if st.startswith('descriptor:') and pending is not None:
            desc = st.split(':', 1)[1].strip()
            head = pending.split('(', 1)[0].split()
            if '(' in desc and head:
                params, ret = parse_descriptor(desc)
                method = (head[-1], params, ret, 'static' in head[:-1])
            pending = None
            continue
        if st == 'RuntimeVisibleAnnotations:':
            mode = 'method' if method else 'class'
            continue
        if _SECTION.match(st):
            mode = None
            continue
        a = _ANNOTATION.match(st)
        if a and mode == 'class' and method is None:
            class_ann.add(a.group(1))
        elif a and mode == 'method' and a.group(1) in MAPPING_ANNOTATIONS and method not in handlers:
            handlers.append(method)
    close()
    return out


def kotlin_handlers(svc, known):
    """Request handlers of the service's Kotlin controllers that are not in the channel model."""
    classes = []
    for c in svc.app_classes:
        if c in known:
            continue
        with open(os.path.join(svc.app, *c.split('.')) + '.class', 'rb') as f:
            data = f.read()
        if KOTLIN_METADATA in data and b'org/springframework/web/bind/annotation/' in data:
            classes.append(c)
    exe = os.path.join(svc.java_home, 'bin', 'javap')
    out = []
    for i in range(0, len(classes), 150):
        r = subprocess.run([exe, '-p', '-v', '-cp', os.pathsep.join(svc.classpath), *classes[i:i + 150]],
                           capture_output=True, text=True, encoding='utf-8', errors='replace')
        out += parse_handlers(r.stdout)
    return out


def class_files(root):
    """Class names under a classes directory (nested classes included)."""
    out = []
    for d, _, files in os.walk(root):
        for f in files:
            if f.endswith('.class') and f != 'module-info.class' and f != 'package-info.class':
                rel = os.path.relpath(os.path.join(d, f), root)[:-6]
                out.append(rel.replace(os.sep, '.'))
    return sorted(out)


def common_package(names):
    pkgs = [n.rsplit('.', 1)[0].split('.') for n in names if '.' in n]
    if not pkgs:
        return ''
    first = pkgs[0]
    k = len(first)
    for p in pkgs[1:]:
        k = min(k, next((i for i, (a, b) in enumerate(zip(first, p)) if a != b), min(len(first), len(p))))
    return '.'.join(first[:k])


def root_packages(names):
    """The packages that hold the given classes: their common package, else (classes under several
    roots, e.g. Juliet's testcases.* and juliet.support.*) each top-level package."""
    common = common_package(names)
    if common:
        return [common]
    return sorted({n.split('.', 1)[0] for n in names if '.' in n})


def pkg(cls):
    return cls.rsplit('.', 1)[0]


def simple(cls):
    return cls.rsplit('.', 1)[-1].split('$')[-1]


# ------------------------------------------------------------------ generation

class Service:
    """Bytecode view of one service: its classes plus the shared modules' classes."""

    def __init__(self, cfg, module, java_home):
        self.name = module['name']
        self.java_home = java_home
        self.app = module['classes']
        self.jar = module.get('jar')
        self.shared = [m['classes'] for m in cfg['shared'] if m.get('classes')]
        self.classpath = [self.app, *self.shared]
        self.app_classes = class_files(self.app)
        self.shared_classes = [c for d in self.shared for c in class_files(d)]
        self.by_simple = {}
        for c in self.app_classes + self.shared_classes:
            self.by_simple.setdefault(simple(c), []).append(c)
        self.info = {}
        self._has = {}

    def load(self, classes):
        need = [c for c in classes if c and c not in self.info]
        if need:
            got = javap(self.java_home, self.classpath, need)
            self.info.update(got)
            for c in need:
                self.info.setdefault(c, None)

    def cls(self, name):
        self.load([name])
        return self.info.get(name)

    def resolve_simple(self, name):
        """FQNs of an application/shared class by simple name; app classes first."""
        hits = self.by_simple.get(name, [])
        return sorted(hits, key=lambda c: (c not in self.app_classes, c))

    def servlet_namespaces(self):
        """'javax' and/or 'jakarta' when an application class extends that HttpServlet."""
        found = []
        for ns in ('javax', 'jakarta'):
            needle = f'{ns}/servlet/http/HttpServlet'.encode()
            for c in self.app_classes:
                with open(os.path.join(self.app, *c.split('.')) + '.class', 'rb') as f:
                    if needle in f.read():
                        found.append(ns)
                        break
        return found

    def has_library(self, prefix):
        if not self.jar:
            return False
        with zipfile.ZipFile(self.jar) as z:
            return any(n.startswith(tuple(p + prefix for p in vkr_config.LIB_PREFIXES)) for n in z.namelist())

    def has_class_or_jdk(self, name):
        """has_class, or a class of the JDK (java.*), which the JRE model always has."""
        return name.startswith('java.') or self.has_class(name)

    def has_class(self, name):
        """True when the service packages the class (its own classes, a shared module or a library jar
        of its Spring Boot jar). A sink on a class the service lacks is not only useless: Tai-e makes a
        phantom class of it, and Micans' Spring model fails on its custom beans (JdbcTemplate) when
        their class is phantom."""
        if name in self._has:
            return self._has[name]
        entry = name.replace('.', '/') + '.class'
        found = name in self.app_classes or name in self.shared_classes
        if not found and self.jar:
            with zipfile.ZipFile(self.jar) as z:
                for n in z.namelist():
                    if not (n.startswith(vkr_config.LIB_PREFIXES) and n.endswith('.jar')):
                        continue
                    try:
                        with z.open(n) as raw, zipfile.ZipFile(io.BytesIO(raw.read())) as lib:
                            found = entry in lib.NameToInfo
                    except zipfile.BadZipFile:  # an unreadable library cannot provide the class
                        continue
                    if found:
                        break
        self._has[name] = found
        return found

    def entity_classes(self):
        out = []
        for c in self.app_classes:
            p = os.path.join(self.app, *c.split('.')) + '.class'
            with open(p, 'rb') as f:
                data = f.read()
            if any(a in data for a in ENTITY_ANNOTATIONS):
                out.append(c)
        return out


def _type_matches(model_type, bytecode_type):
    """channel-extractor writes source-level simple types (List, Long, CouponRequest)."""
    mt = re.sub(r'<.*', '', model_type or '').strip()
    mt = mt.replace('...', '[]')
    return simple(bytecode_type.replace('[]', '')) == simple(mt.replace('[]', '')) or not mt


def _pick_method(info, name, params):
    cands = info.find(name, len(params)) if info else []
    if len(cands) > 1:
        cands = [m for m in cands if all(_type_matches(p.get('type'), t) for p, t in zip(params, m[1]))] or cands
    return cands[0] if cands else None


def generate(cfg, model, module, java_home):
    """(config text, discovery dict, notes) for one service."""
    svc = Service(cfg, module, java_home)
    name = svc.name
    notes = []
    gateway = any(e.get('exposedViaGateway') for e in model.get('endpoints', []))
    endpoints = [e for e in model.get('endpoints', []) if e['service'] == name
                 and (e.get('exposedViaGateway') or not gateway)]
    if not gateway:
        notes.append('no endpoint of the model is exposed through a gateway: every endpoint is a source')
    listeners = [l for l in model.get('listeners', []) if l['service'] == name]
    clients = [c for c in model.get('httpClients', []) if c['service'] == name]
    publishers = [p for p in model.get('publishers', []) if p['service'] == name]
    svc.load([e['handler']['className'] for e in endpoints] + [l['handler']['className'] for l in listeners]
             + [c['declaration']['className'] for c in clients]
             + [p['sendSite']['className'] for p in publishers])

    def is_enum(t):
        if '.' not in t or t.startswith('java.'):
            return False
        i = svc.cls(t)
        return bool(i and i.is_enum)

    # ---- sources
    sources, dto_classes = [], set()
    for e in sorted(endpoints, key=lambda e: (e['handler']['className'], e['handler']['line'])):
        h = e['handler']
        m = _pick_method(svc.info.get(h['className']), h['method'], e['params'])
        if not m:
            notes.append(f'endpoint {e["id"]}: {h["className"]}#{h["method"]} with {len(e["params"])} parameters '
                         f'not found in the bytecode')
            continue
        for i, (p, t) in enumerate(zip(e['params'], m[1])):
            if p.get('source') == 'FRAMEWORK' or t in PRIMITIVES or t in FRAMEWORK_TYPES or is_enum(t):
                continue
            sources.append({'kind': 'param', 'method': tai_sig(h['className'], m), 'index': i})
            if (t in svc.app_classes or t in svc.shared_classes) and not t.endswith('[]'):
                dto_classes.add(t)
    known = {e['handler']['className'] for e in model.get('endpoints', []) if e['service'] == name}
    kotlin = kotlin_handlers(svc, known)
    if kotlin and gateway:
        notes.append(f'{len(kotlin)} Kotlin request handler(s) not in the channel model: gateway exposure '
                     f'unknown, not sources')
    elif kotlin:
        notes.append(f'{len(kotlin)} Kotlin request handler(s) read from the bytecode (not in the channel model)')
        for cls, m in kotlin:
            for i, t in enumerate(m[1]):
                if t in PRIMITIVES or t in FRAMEWORK_TYPES or is_enum(t) or t.startswith('kotlin.coroutines.'):
                    continue
                entry = {'kind': 'param', 'method': tai_sig(cls, m), 'index': i}
                if entry not in sources:
                    sources.append(entry)
                if (t in svc.app_classes or t in svc.shared_classes) and not t.endswith('[]'):
                    dto_classes.add(t)
    servlet_ns = svc.servlet_namespaces()
    servlet_sources = [{'kind': 'call', 'method': f'<{cls.format(ns=ns)}: {sig.format(ns=ns)}>', 'index': 'result',
                        **({'type': t} if t else {})}
                       for ns in servlet_ns for cls, sig, t in SERVLET_SOURCES]
    if servlet_ns:
        notes.append(f'servlet application ({", ".join(servlet_ns)}): the request getters are sources '
                     f'and every doGet/doPost/... of an HttpServlet subclass is an entry point')
    listener_sources = []
    payload_types = set()
    for l in sorted(listeners, key=lambda l: (l['handler']['className'], l['handler']['line'])):
        h = l['handler']
        info = svc.info.get(h['className'])
        cands = info.find(h['method']) if info else []
        hit = None
        for m in cands:
            for i, t in enumerate(m[1]):
                if simple(t) == (l.get('payloadType') or ''):
                    hit = (m, i)
                    break
            if hit:
                break
        if not hit and len(cands) == 1 and len(cands[0][1]) == 1:
            hit = (cands[0], 0)
        if not hit:
            notes.append(f'listener {l["id"]}: payload parameter of {h["className"]}#{h["method"]} not found')
            continue
        entry = {'kind': 'param', 'method': tai_sig(h['className'], hit[0]), 'index': hit[1]}
        if entry not in listener_sources:
            listener_sources.append(entry)
        payload_types.add(l.get('payloadType'))
    payload_types |= {p.get('payloadType') for p in publishers}

    # ---- discovery: DTO, event, entity classes and packages
    pk = cfg['taint']['packages']
    dto_packages = sorted(pk['dto'] or {pkg(c) for c in dto_classes})
    event_classes = sorted({c for t in payload_types if t for c in svc.resolve_simple(t)[:1]})
    event_packages = sorted(pk['event'] or {pkg(c) for c in event_classes})
    entity_classes = svc.entity_classes()
    entity_packages = sorted(pk['entity'] or {pkg(c) for c in entity_classes})
    in_event_pkgs = [c for c in svc.app_classes + svc.shared_classes if pkg(c) in event_packages and '$' not in c]
    svc.load(in_event_pkgs)
    max_ctor = max([len(m[1]) for c in in_event_pkgs if svc.info.get(c)
                    for m in svc.info[c].find('<init>')] or [0])

    # ---- sinks
    sink_groups = []
    feign = []
    rest_template = [c for c in clients if c.get('clientKind') == 'REST_TEMPLATE']
    for c in clients:
        if c in rest_template:
            continue
        d = c['declaration']
        m = _pick_method(svc.info.get(d['className']), d['method'], c.get('params') or [])
        if not m:
            notes.append(f'http client {c["id"]}: {d["className"]}#{d["method"]} not found in the bytecode')
            continue
        for i, t in enumerate(m[1]):
            p = (c.get('params') or [{}] * len(m[1]))[i]
            if t not in PRIMITIVES and p.get('source') != 'FRAMEWORK':
                e = {'method': tai_sig(d['className'], m), 'index': i}
                if e not in feign:
                    feign.append(e)
    if feign:
        sink_groups.append(('outgoing HTTP channel (OpenFeign)', feign))
    rest_template = rest_template and svc.has_class(RT)
    if rest_template:
        sink_groups.append(('outgoing HTTP channel (RestTemplate: URL and request body)',
                            [{'method': m, 'index': i} for m, i in REST_TEMPLATE_SINKS]))
    amqp = []
    outbox_sinks = []
    outbox_pubs = [p for p in publishers if p.get('mechanism') == 'OUTBOX']
    if outbox_pubs:
        svc_cands = [c for suffix, _, _ in OUTBOX_SIGNATURES for c in svc.app_classes + svc.shared_classes
                     if simple(c).endswith(suffix) and '$' not in c]
        svc.load(svc_cands)
        enqueue = []
        for suffix, meth, idx in OUTBOX_SIGNATURES:
            for c in svc_cands:
                i = svc.info.get(c)
                for m in (i.find(meth) if i else []):
                    if len(m[1]) > idx:
                        enqueue.append((c, m, idx))
        payload_param_types = {m[1][idx] for _, m, idx in enqueue}
        # Publisher whose origin differs from its send site: channel-extractor resolved the routing
        # key through the callers of a helper. When the helper receives the payload as a parameter,
        # the sink is put on that parameter, so that each call site maps to one publisher id;
        # a helper that builds the payload itself keeps the sink at the enqueue call.
        needs_enqueue = False
        for p in outbox_pubs:
            s = p['sendSite']
            hit = None
            if (s['className'], s['method']) != (p['origin']['className'], p['origin']['method']):
                info = svc.info.get(s['className'])
                payload_fqn = set(svc.resolve_simple(p.get('payloadType') or '')[:1])
                for m in (info.find(s['method']) if info else []):
                    idx = next((i for i, t in enumerate(m[1]) if t in payload_param_types | payload_fqn), None)
                    if idx is not None:
                        hit = {'method': tai_sig(s['className'], m), 'index': idx}
            if hit is None:
                needs_enqueue = True
            elif hit not in amqp:
                amqp.append(hit)
                outbox_sinks.append([s['className'], s['method']])
        if needs_enqueue:
            if not enqueue:
                notes.append('outbox publishers found, but no <...OutboxService>.enqueue method in the bytecode')
            for c, m, idx in enqueue:
                amqp.append({'method': tai_sig(c, m), 'index': idx})
                outbox_sinks.append([c, m[0]])
    if svc.has_library('spring-amqp-') or svc.has_library('spring-rabbit-'):
        amqp += [{'method': '<org.springframework.amqp.core.AmqpTemplate^: void convertAndSend(java.lang.String,java.lang.String,java.lang.Object)>', 'index': 2},
                 {'method': '<org.springframework.amqp.core.AmqpTemplate^: void convertAndSend(java.lang.String,java.lang.Object)>', 'index': 1}]
    if amqp:
        sink_groups.append(('outgoing AMQP channel' + (' (transactional outbox: enqueue = send)' if outbox_pubs else ''),
                            amqp))
    mybatis_methods = []
    for cat in cfg['taint']['sinks']:
        if cat in CATALOGUES:
            for title, entries in CATALOGUES[cat]:
                kept = [e for e in entries if svc.has_class_or_jdk(e['method'][1:].split(':')[0].rstrip('^'))]
                if kept:
                    sink_groups.append((title, kept))
            if cat == 'cwe-89':
                mb, mybatis_methods = mybatis_sinks(svc, notes)
                if mb:
                    sink_groups.append(('SQL injection: MyBatis ${} substitution', mb))
        else:
            from vkr_yaml import load
            sink_groups.append((f'catalogue {os.path.basename(cat)}', (load(cat) or {}).get('sinks') or []))
    if servlet_ns and 'cwe-79' in cfg['taint']['sinks']:
        sink_groups.append(('XSS (CWE-79): the text a servlet writes to its response writer', SERVLET_SINKS))

    # ---- transfers
    transfer_groups = []
    acc = [{'method': f'<{p}.*: * *()>', 'from': 'base', 'to': 'result'} for p in dto_packages + event_packages]
    if acc:
        transfer_groups.append(('DTO / event record accessors carry taint from the record to its components', acc))
    if publishers and event_packages:
        transfer_groups.append((
            'an event built from a tainted component is tainted (the payload crosses the channel as a whole)',
            [{'method': f'<{p}.*: void <init>(*{{{n}+}})>', 'from': n - 1, 'to': 'base'}
             for p in event_packages for n in range(1, max_ctor + 1)]))
    if rest_template:
        transfer_groups.append(('a request entity built from a tainted body is tainted',
                                [{'method': m, 'from': f, 'to': t} for m, f, t in REST_TEMPLATE_TRANSFERS]))
    for title, items in JDK_TRANSFERS:
        transfer_groups.append((title, [{'method': m, 'from': f, 'to': t} for m, f, t in items]))
    for title, items in SERVLET_TRANSFERS if servlet_ns else []:
        kept = [x for x in items if svc.has_class_or_jdk(x[0][1:].split(':')[0].rstrip('^'))]
        transfer_groups.append((f'servlet: {title}', [{'method': x[0], 'from': x[1], 'to': x[2],
                                                        **({'type': x[3]} if len(x) > 3 else {})} for x in kept]))
    sanitizers = []
    for cat in cfg['taint']['sinks']:
        kept = [(m, f, t) for m, f, t in CATALOGUE_TRANSFERS.get(cat, [])
                if svc.has_class_or_jdk(m[1:].split(':')[0])]
        if kept:
            transfer_groups.append((f'{cat}: request text to the URL / template it becomes',
                                    [{'method': m, 'from': f, 'to': t} for m, f, t in kept]))
        sanitizers += [e for e in CATALOGUE_SANITIZERS.get(cat, []) if svc.has_class_or_jdk(e['method'][1:].split(':')[0])]

    lines = [f'# Generated by gen_taint_config.py for {name} - do not edit; add entries to the overrides file.',
             f'# Sources = externally reachable endpoints + AMQP listeners of the channel model; sinks = sink',
             f'# catalogues ({", ".join(os.path.basename(s) for s in cfg["taint"]["sinks"])}) + outgoing channel ends.',
             '', 'call-site-mode: true', '', 'sources:']
    if sources:
        lines.append('  # HTTP endpoints')
        lines += [f'  - {dump_entry(e)}' for e in sources]
    if servlet_sources:
        lines.append('  # servlet request getters')
        lines += [f'  - {dump_entry(e)}' for e in servlet_sources]
    if listener_sources:
        lines.append('  # AMQP listener payloads (replaced by the event field sources in the field-level config)')
        lines += [f'  - {dump_entry(e)}  # AMQP listener' for e in listener_sources]
    if dto_packages:
        lines.append('  # request-DTO field sources are generated (scripts/mk_dto_fields.py)')
    lines += ['', 'sinks:']
    for title, items in sink_groups:
        lines.append(f'  # {title}')
        lines += [f'  - {dump_entry(e)}' for e in items]
    lines += ['', 'transfers:']
    for title, items in transfer_groups:
        lines.append(f'  # {title}')
        lines += [f'  - {dump_entry(e)}' for e in items]
    if sanitizers:
        lines += ['', 'sanitizers:', '  # output encoders']
        lines += [f'  - {dump_entry(e)}' for e in sanitizers]
    if 'cwe-79' in cfg['taint']['sinks']:
        # also makes the run key differ: the plugin is not part of the YAML
        lines += ['', '# response-body sinks (CWE-79): vkr.plugin.ResponseBodySink']
    app_packages = sorted({p for names in [svc.app_classes] + [class_files(d) for d in svc.shared]
                           for p in root_packages(names)})
    discovery = {'service': name, 'appPackages': app_packages, 'dtoPackages': dto_packages,
                 'dtoClasses': sorted(dto_classes), 'eventPackages': event_packages, 'eventClasses': event_classes,
                 'entityPackages': entity_packages, 'entityClasses': entity_classes,
                 'outboxSinks': outbox_sinks, 'mybatisSinks': mybatis_methods, 'notes': notes}
    return '\n'.join(lines) + '\n', discovery, notes


MYBATIS_STATEMENTS = ('select', 'insert', 'update', 'delete')
MYBATIS_SUBST = re.compile(r'\$\{\s*([^},\s]+)')
MYBATIS_IMPLICIT = {'_parameter': 0}  # the single parameter object itself


def mapper_param_names(java_home, classpath, classes, chunk=150):
    """{interface: {(method, (param types)): [name of each parameter]}} of mapper interfaces: the @Param
    value, else the compiled name (MethodParameters, javac -parameters; MyBatis' useActualParamName),
    else None. One javap JVM per chunk of classes."""
    result = {}
    classes = sorted(set(classes))
    for i in range(0, len(classes), chunk):
        r = subprocess.run([os.path.join(java_home, 'bin', 'javap'), '-v', '-p', '-cp', os.pathsep.join(classpath),
                            *classes[i:i + chunk]], capture_output=True, text=True, encoding='utf-8', errors='replace')
        for block in re.split(r'^(?=Classfile )', r.stdout, flags=re.M):
            m = re.search(r'^\S.*\binterface\s+([\w.$]+)', block, re.M)
            if m:
                result[m.group(1)] = _param_names(block)
    return result


def _param_names(text):
    out, key, names, param, section, pending_name = {}, None, [], None, None, None
    for line in text.splitlines():
        s = line.strip()
        if s.startswith('descriptor:') and '(' in s:
            if key:
                out[key] = names
            params, _ = parse_descriptor(s.split(':', 1)[1].strip())
            key, names, section = (pending_name, tuple(params)), [None] * len(params), None
        elif line.startswith('  ') and not line.startswith('   ') and '(' in s:
            pending_name = s.split('(', 1)[0].split()[-1]
        elif key is None:
            continue
        elif s == 'MethodParameters:':
            section, param = 'names', 0
        elif s == 'RuntimeVisibleParameterAnnotations:':
            section = 'annotations'
        elif section == 'names' and s and not s.startswith('Name ') and param < len(names) \
                and line.startswith('      ') and re.fullmatch(r'[\w$]+(\s+\w+)*', s):
            names[param] = s.split()[0]
            param += 1
        elif section == 'annotations' and (m := re.match(r'parameter (\d+):', s)):
            param = int(m.group(1))
        elif section == 'annotations' and (m := re.match(r'value="([^"]*)"', s)) and param < len(names):
            names[param] = m.group(1)  # only org.apache.ibatis.annotations.Param has a value here in practice
        elif not line.startswith('    '):
            section = None
    if key:
        out[key] = names
    return out


def _mapper_statements(path):
    """(namespace, [(statement id, [${} roots that are not foreach/bind locals])]) of a MyBatis XML mapper."""
    import xml.etree.ElementTree as ET
    try:
        root = ET.parse(path).getroot()  # expat: no external DTD fetch, entity expansion bounded
    except ET.ParseError:
        return None, []
    if root.tag != 'mapper' or not root.get('namespace'):
        return None, []
    ns = root.get('namespace')
    fragments = {e.get('id'): e for e in root.iter('sql') if e.get('id')}

    def walk(e, local, seen, out):
        local = set(local) | {c.get('name') for c in e if c.tag == 'bind' and c.get('name')}
        if e.tag == 'foreach':
            local |= {e.get('item'), e.get('index')} - {None}
        if e.tag == 'include':
            ref = (e.get('refid') or '').removeprefix(ns + '.')
            if ref in fragments and ref not in seen:
                walk(fragments[ref], local, seen | {ref}, out)
        for text in (e.text, *(c.tail for c in e)):
            for m in MYBATIS_SUBST.finditer(text or ''):
                path = re.split(r'[.\[]', m.group(1))
                if path[0] not in local:
                    out.append(path)
        for c in e:
            walk(c, local, seen, out)

    stmts = []
    for e in root:
        if e.tag in MYBATIS_STATEMENTS and e.get('id'):
            roots = []
            walk(e, set(), frozenset(), roots)
            if roots:
                stmts.append((e.get('id'), roots))
    return ns, stmts


def _has_field(svc, cls, field):
    seen = set()
    while cls and cls not in seen and not cls.startswith('java.'):
        seen.add(cls)
        info = svc.cls(cls)
        if not info:
            return False
        if any(f[1] == field for f in info.fields):
            return True
        m = re.search(r'\bextends\s+([\w.$]+)', info.header)
        cls = m.group(1) if m and not info.is_interface else None
    return False


def mybatis_sinks(svc, notes):
    """XML mappers of the service and the shared modules: ${...} splices text into the SQL, so the
    mapper method (namespace = interface, id = method) is a CWE-89 sink at the parameter the expression
    names: a named parameter (@Param, or the compiled name) or param<N>/arg<N>; with one parameter, the
    expression is a property of it (index "0.<field>": the taint sits in that field, e.g. the
    orderByClause of a MyBatis Generator Example) or the parameter itself. foreach items and bind
    variables are skipped: they are built by the mapper (MyBatis Generator's ${criterion.condition}
    is a constant of the generated criteria). Returns (sink entries, [[interface, method]])."""
    out, methods, mappers = [], [], []
    for d in [svc.app, *svc.shared]:
        for dirpath, _, files in os.walk(d):
            for f in sorted(files):
                if f.endswith('.xml'):
                    ns, stmts = _mapper_statements(os.path.join(dirpath, f))
                    if stmts:
                        mappers.append((ns, stmts))
    if not mappers:
        return out, methods
    svc.load([ns for ns, _ in mappers])
    all_names = mapper_param_names(svc.java_home, svc.classpath, [ns for ns, _ in mappers])
    for ns, stmts in mappers:
        info = svc.cls(ns)
        if not info:
            notes.append(f'MyBatis mapper {ns}: interface not on the classpath')
            continue
        names = all_names.get(ns, {})
        for sid, roots in stmts:
            ms = info.find(sid)
            if not ms:
                notes.append(f'MyBatis {ns}.{sid}: mapper method not found')
            for m in ms:
                pnames = names.get((m[0], tuple(m[1])), [None] * len(m[1]))
                for path in roots:
                    idx, rest = None, path[1:]
                    if path[0] in pnames:
                        idx = pnames.index(path[0])
                    elif pm := re.fullmatch(r'param(\d+)', path[0]):
                        idx = int(pm.group(1)) - 1
                    elif am := re.fullmatch(r'arg(\d+)', path[0]):
                        idx = int(am.group(1))
                    elif path[0] in MYBATIS_IMPLICIT or len(m[1]) == 1:
                        idx = 0
                        rest = path[1:] if path[0] in MYBATIS_IMPLICIT else path
                    if idx is None or not 0 <= idx < len(m[1]):
                        notes.append(f'MyBatis {ns}.{sid}: ${{{".".join(path)}}} names no parameter')
                        continue
                    ptype = m[1][idx]
                    index = idx
                    if rest and _has_field(svc, ptype, rest[0]):
                        index = f'{idx}.{rest[0]}'
                    elif ptype in PRIMITIVES:
                        continue  # a number spliced into SQL is no injection
                    e = {'method': tai_sig(ns, m), 'index': index}
                    if e not in out:
                        out.append(e)
                    if [ns, m[0]] not in methods:
                        methods.append([ns, m[0]])
    return out, methods


def generate_all(cfg, model, out_dir, services=None):
    java_home = cfg['toolchain']['java_home']
    os.makedirs(out_dir, exist_ok=True)
    results = {}
    for m in cfg['services']:
        if services and m['name'] not in services:
            continue
        text, disc, notes = generate(cfg, model, m, java_home)
        with open(os.path.join(out_dir, f'taint-{m["name"]}.yml'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(text)
        with open(os.path.join(out_dir, f'discovery-{m["name"]}.json'), 'w', encoding='utf-8') as f:
            json.dump(disc, f, indent=1)
        results[m['name']] = (text, disc)
        for n in notes:
            print(f'{m["name"]}: note: {n}', file=sys.stderr)
    return results


def main():
    ap = argparse.ArgumentParser(description='generates taint-<service>.yml from the channel model')
    ap.add_argument('--config')
    ap.add_argument('--model', required=True)
    ap.add_argument('--services')
    ap.add_argument('--out-dir', required=True)
    a = ap.parse_args()
    try:
        cfg = vkr_config.load(a.config, require=('java', 'artifacts'))
    except vkr_config.ConfigError as e:
        sys.exit(f'vkr-taint: {e}')
    model = json.load(open(a.model, encoding='utf-8'))
    res = generate_all(cfg, model, a.out_dir, a.services.split(',') if a.services else None)
    for name, (text, _) in res.items():
        n = {s: 0 for s in ('sources', 'sinks', 'transfers')}
        sec = None
        for line in text.splitlines():
            if line.rstrip(':') in n and not line.startswith(' '):
                sec = line.rstrip(':')
            elif line.startswith('  - ') and sec:
                n[sec] += 1
        print(f'{name}: {n["sources"]} sources, {n["sinks"]} sinks, {n["transfers"]} transfers -> '
              f'{os.path.join(a.out_dir, "taint-" + name + ".yml")}')


if __name__ == '__main__':
    main()
