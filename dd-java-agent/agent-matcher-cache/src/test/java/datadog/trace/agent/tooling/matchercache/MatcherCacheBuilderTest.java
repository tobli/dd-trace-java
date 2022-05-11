package datadog.trace.agent.tooling.matchercache;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollection;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassCollectionLoader;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassFinder;
import datadog.trace.agent.tooling.matchercache.util.BinarySerializers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MatcherCacheBuilderTest {
  public final String TEST_CLASSES_FOLDER =
      this.getClass().getClassLoader().getResource("test-classes").getFile();

  private static class TestClassLoader extends ClassCollectionLoader {
    public TestClassLoader(ClassCollection classCollection, int javaMajorVersion) {
      super(classCollection, javaMajorVersion);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if ("example.InnerJarClass".equals(name)) {
        throw new RuntimeException("Intentional test class load failure");
      }
      return super.loadClass(name, resolve);
    }
  }

  private static class TestClassMatchers implements ClassMatchers {
    @Override
    public boolean matchesAny(Class<?> cl) {
      TypeDescription typeDescription = TypeDescription.ForLoadedType.of(cl);
      String fullClassName = typeDescription.getCanonicalName();
      if (fullClassName != null) {
        switch (fullClassName) {
          case "example.OuterJarClass":
          case "example.InnerJarClass":
          case "example.classes.Abc":
          case "foo.bar.FooBar":
            return true;
          case "example.MiddleJarClass":
          case "example.classes.Only9":
            return false;
        }
      }
      return false;
    }

    @Override
    public boolean isGloballyIgnored(String fullClassName) {
      switch (fullClassName) {
        case "foo.bar.xyz.Xyz":
        case "bar.foo.Baz":
          return true;
      }
      return false;
    }
  }

  @Test
  public void testHappyPath() throws IOException {
    File classPath = new File(TEST_CLASSES_FOLDER);

    ClassFinder classFinder = new ClassFinder();
    ClassCollection classCollection = classFinder.findClassesIn(classPath);
    int javaMajorVersion = 9;
    String agentVersion = "0.95.0";
    ClassLoader classLoader = new TestClassLoader(classCollection, javaMajorVersion);
    MatcherCacheBuilder matcherCacheBuilder =
        new MatcherCacheBuilder(javaMajorVersion, agentVersion);
    MatcherCacheBuilder.Stats stats =
        matcherCacheBuilder.fill(classCollection, classLoader, new TestClassMatchers());

    assertEquals("Ignore: 2; Skip: 2; Transform: 3; Fail: 1", stats.toString());

    // serialize MatcherCache and load as MatcherCache and check the result
    MatcherCache matcherCache =
        serializeAndLoadCacheData(matcherCacheBuilder, javaMajorVersion, agentVersion);

    assertEquals(false, matcherCache.isIgnored("example.OuterJarClass"));
    assertEquals(false, matcherCache.isIgnored("example.InnerJarClass"));
    assertEquals(true, matcherCache.isIgnored("example.MiddleJarClass"));
    assertEquals(true, matcherCache.isIgnored("example.NonExistingClass"));

    assertEquals(false, matcherCache.isIgnored("example.classes.Abc"));
    assertEquals(true, matcherCache.isIgnored("example.classes.Only9"));
    assertEquals(true, matcherCache.isIgnored("example.classes.NonExistingClass"));

    assertEquals(true, matcherCache.isIgnored("foo.bar.Baz"));
    assertEquals(false, matcherCache.isIgnored("foo.bar.FooBar"));
    assertEquals(true, matcherCache.isIgnored("foo.bar.NonExistingClass"));

    assertEquals(true, matcherCache.isIgnored("foo.bar.xyz.Xyz"));
    assertEquals(true, matcherCache.isIgnored("foo.bar.xyz.NonExistingClass"));

    assertNull(matcherCache.isIgnored("non.existing.package.Foo"));
    assertNull(matcherCache.isIgnored("non.existing.package.Bar"));

    // serialize text report
    Pattern expectedPattern =
        Pattern.compile(
            "Matcher Cache Report\n"
                + "Format Version: 1\n"
                + "Agent Version: 0\\.95\\.0\n"
                + "Java Major Version: 9\n"
                + "Packages: 5\n"
                + "bar.foo.Baz,IGNORE,.*/test-classes/relocated-classes/somefolder/Baz.class\n"
                + "example.InnerJarClass,FAIL,.*/test-classes/inner-jars/example.jar/Middle.jar/InnerJarClass.jar"
                + ",java.lang.RuntimeException: Intentional test class load failure\n"
                + "example.MiddleJarClass,SKIP,.*/test-classes/inner-jars/example.jar/Middle.jar\n"
                + "example.OuterJarClass,TRANSFORM,.*/test-classes/inner-jars/example.jar\n"
                + "example.classes.Abc,TRANSFORM,.*/test-classes/multi-release-jar/multi-release.jar\n"
                + "example.classes.Only9,SKIP,.*/test-classes/multi-release-jar/multi-release.jar\n"
                + "foo.bar.FooBar,TRANSFORM,.*/test-classes/renamed-class-file/renamed-foobar-class.bin\n"
                + "foo.bar.xyz.Xyz,IGNORE,.*/test-classes/standard-layout/foo/bar/xyz/Xyz.class\n");
    String reportData = serializeTextReport(matcherCacheBuilder);
    assertTrue(
        expectedPattern.matcher(reportData).matches(),
        "Expected:\n" + expectedPattern + "Actual:\n" + reportData);
  }

  @Test
  public void testFailIfUnexpectedDataFormatVersion() throws IOException {
    int incorrectMatcherCacheDataFormat = MatcherCacheBuilder.MATCHER_CACHE_FILE_FORMAT_VERSION + 1;
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    BinarySerializers.writeInt(os, incorrectMatcherCacheDataFormat);
    ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
    int whateverJavaVersion = 11;
    Assertions.assertThrows(
        MatcherCache.UnexpectedDataFormatVersion.class,
        () -> MatcherCache.deserialize(is, whateverJavaVersion, "whatever-agent-version"));
  }

  @Test
  public void testFailIfCacheDataIsForAnotherJavaVersion() {
    int javaMajorVersion = 9;
    MatcherCacheBuilder matcherCacheBuilder =
        new MatcherCacheBuilder(javaMajorVersion, "whatever-agent-version");

    int anotherJavaMajorVersion = 8;
    assertNotEquals(anotherJavaMajorVersion, javaMajorVersion);

    Assertions.assertThrows(
        MatcherCache.IncompatibleJavaVersionData.class,
        () ->
            serializeAndLoadCacheData(
                matcherCacheBuilder, anotherJavaMajorVersion, "whatever-agent-version"));
  }

  @Test
  public void testFailIfCacheDataBuiltWithDifferentAgentVersion() throws IOException {
    int javaMajorVersion = 11;
    MatcherCacheBuilder matcherCacheBuilder =
        new MatcherCacheBuilder(javaMajorVersion, "whatever-agent-version");

    String agentVersion = "0.95.0";
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    matcherCacheBuilder.serializeBinary(os);

    String anotherAgentVersion = "0.95.1";
    assertNotEquals(anotherAgentVersion, agentVersion);
    Assertions.assertThrows(
        MatcherCache.IncompatibleAgentVersion.class,
        () -> {
          ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
          MatcherCache.deserialize(is, javaMajorVersion, anotherAgentVersion);
        });
  }

  private String serializeTextReport(MatcherCacheBuilder matcherCacheBuilder) {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    matcherCacheBuilder.serializeText(os);
    return new String(os.toByteArray(), StandardCharsets.UTF_8);
  }

  private MatcherCache serializeAndLoadCacheData(
      MatcherCacheBuilder matcherCacheBuilder, int javaMajorVersion, String agentVersion)
      throws IOException {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    matcherCacheBuilder.serializeBinary(os);
    ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
    return MatcherCache.deserialize(is, javaMajorVersion, agentVersion);
  }
}
