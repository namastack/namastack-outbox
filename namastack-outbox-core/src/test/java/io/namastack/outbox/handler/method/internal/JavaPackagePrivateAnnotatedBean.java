package io.namastack.outbox.handler.method.internal;

@SuppressWarnings("unused")
public class JavaPackagePrivateAnnotatedBean {

    @TestJavaAnnotation("java-method-1")
    void annotatedMethod1(String param) {
    }

    @TestJavaAnnotation("java-method-2")
    void annotatedMethod2(String param) {
    }

    void nonAnnotatedMethod() {
    }
}
