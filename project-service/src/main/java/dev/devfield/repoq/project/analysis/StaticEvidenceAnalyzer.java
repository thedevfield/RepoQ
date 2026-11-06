package dev.devfield.repoq.project.analysis;

import static dev.devfield.repoq.project.analysis.AnalysisSnapshots.*;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import dev.devfield.repoq.project.github.GitHubSourceClient.Collected;
import dev.devfield.repoq.project.github.GitHubSourceClient.TextFile;
import java.io.StringReader;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import javax.tools.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/** Parse and attribute symbols only; never generate bytecode, run processors or execute repository code. */
@Component
public class StaticEvidenceAnalyzer {
    private record Rule(String summary, String topic) {}
    private static final Map<String, Rule> ANNOTATIONS = Map.ofEntries(
            Map.entry("org.springframework.boot.autoconfigure.SpringBootApplication", new Rule("Declares a Spring Boot application.", "API_BACKEND")),
            Map.entry("org.springframework.web.bind.annotation.RestController", new Rule("Declares a Spring REST controller.", "API_BACKEND")),
            Map.entry("org.springframework.stereotype.Controller", new Rule("Declares a Spring controller.", "API_BACKEND")),
            Map.entry("org.springframework.stereotype.Service", new Rule("Declares a Spring service component.", "API_BACKEND")),
            Map.entry("org.springframework.stereotype.Repository", new Rule("Declares a Spring repository component.", "DATABASE")),
            Map.entry("org.springframework.security.config.annotation.web.configuration.EnableWebSecurity", new Rule("Declares Spring web security configuration.", "SECURITY")),
            Map.entry("org.springframework.transaction.annotation.Transactional", new Rule("Declares a transactional boundary.", "DATABASE")),
            Map.entry("org.springframework.scheduling.annotation.Async", new Rule("Declares asynchronous execution through Spring.", "ASYNC")));
    private static final Map<String, Rule> TYPES = Map.ofEntries(
            Map.entry("org.springframework.jdbc.core.JdbcTemplate", new Rule("Declares a JdbcTemplate dependency.", "DATABASE")),
            Map.entry("jakarta.persistence.EntityManager", new Rule("Declares a JPA EntityManager dependency.", "DATABASE")),
            Map.entry("org.springframework.data.jpa.repository.JpaRepository", new Rule("Extends the Spring Data JPA repository contract.", "DATABASE")),
            Map.entry("org.springframework.security.web.SecurityFilterChain", new Rule("Declares a Spring Security filter chain type.", "SECURITY")),
            Map.entry("java.net.http.HttpClient", new Rule("Declares a Java HTTP client dependency.", "EXTERNAL_INTEGRATION")),
            Map.entry("org.springframework.web.client.RestClient", new Rule("Declares a Spring REST client dependency.", "EXTERNAL_INTEGRATION")),
            Map.entry("org.springframework.web.client.RestTemplate", new Rule("Declares a Spring REST template dependency.", "EXTERNAL_INTEGRATION")),
            Map.entry("org.springframework.web.reactive.function.client.WebClient", new Rule("Declares a Spring WebClient dependency.", "EXTERNAL_INTEGRATION")));

    public boolean available() { return ToolProvider.getSystemJavaCompiler() != null; }

    public Draft analyze(List<Collected> repositories) {
        List<Member> members = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        List<Coverage> coverage = new ArrayList<>();
        Map<String, Topic> topics = new TreeMap<>();
        for (Collected repository : repositories) {
            Member member = new Member(UUID.randomUUID(), repository.target().repositoryId(),
                    repository.target().githubRepositoryId(), repository.commitSha());
            members.add(member);
            Map<String, Integer> exclusions = new TreeMap<>(repository.exclusions());
            for (TextFile file : repository.files()) {
                if (file.path().endsWith(".java")) {
                    if (!java(member, file, evidence, topics)) exclusions.merge("unparsed_java", 1, Integer::sum);
                } else if (file.path().toLowerCase(Locale.ROOT).endsWith(".md")) {
                    String[] lines = file.text().split("\n");
                    for (int i = 0; i < lines.length && evidence.size() < 200; i++) {
                        if (Pattern.compile("(?i)\\b(spring|java|redis|postgresql|mysql|kafka|docker)\\b").matcher(lines[i]).find()) {
                            evidence.add(new Evidence(UUID.randomUUID(), member.id(), file.path(), i + 1, i + 1,
                                    ClaimType.DOC_CLAIM, "Documentation mentions a technology; implementation is not established by this claim."));
                            break;
                        }
                    }
                } else dependency(member, file, evidence);
            }
            coverage.add(new Coverage(member.id(), repository.files().size(),
                    repository.exclusions().values().stream().mapToInt(Integer::intValue).sum(),
                    repository.files().stream().map(TextFile::path).toList(), exclusions));
        }
        return new Draft(members, evidence, List.copyOf(topics.values()), List.of(), List.of(), coverage);
    }

    private boolean java(Member member, TextFile file, List<Evidence> evidence, Map<String, Topic> topics) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Java source analysis requires JDK 21");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return file.text(); }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            manager.setLocation(StandardLocation.CLASS_PATH, List.of());
            manager.setLocation(StandardLocation.SOURCE_PATH, List.of());
            var task = (JavacTask) compiler.getTask(null, trustedSymbols(manager), diagnostics,
                    List.of("-proc:none", "--release", "21"), null, List.of(source));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) return false;
            task.analyze();
            var trees = Trees.instance(task);
            var positions = trees.getSourcePositions();
            for (CompilationUnitTree unit : units) {
                Set<String> emitted = new HashSet<>();
                new TreePathScanner<Void, Void>() {
                    String resolved(Tree type) {
                        if (type == null) return "";
                        var path = com.sun.source.util.TreePath.getPath(unit, type);
                        if (path == null) return "";
                        var mirror = trees.getTypeMirror(path);
                        if (mirror == null || mirror.getKind() != javax.lang.model.type.TypeKind.DECLARED) return "";
                        var element = ((javax.lang.model.type.DeclaredType) mirror).asElement();
                        if (!(element instanceof javax.lang.model.element.TypeElement declaration) || trees.getPath(declaration)!=null) return "";
                        // An unresolved ancestor could declare a member type that hides an import.
                        for (var scope=getCurrentPath();scope!=null;scope=scope.getParentPath()) {
                            if (!(scope.getLeaf() instanceof ClassTree)) continue;
                            var owner=trees.getElement(scope);
                            if (owner instanceof javax.lang.model.element.TypeElement enclosing && !knownAncestors(enclosing,new HashSet<>())) return "";
                        }
                        return declaration.getQualifiedName().toString();
                    }
                    void emit(Tree node, Rule rule) {
                        if (rule == null || evidence.size() >= 200 || !emitted.add(rule.summary())) return;
                        long position = positions.getStartPosition(unit, node);
                        if (position < 0) return;
                        int line = (int) unit.getLineMap().getLineNumber(position);
                        Evidence e = new Evidence(UUID.randomUUID(), member.id(), file.path(), line, line, ClaimType.CODE_FACT, rule.summary());
                        evidence.add(e); topics.putIfAbsent(rule.topic(), new Topic(rule.topic(), true, e.id()));
                    }
                    @Override public Void visitClass(ClassTree node, Void unused) {
                        for (AnnotationTree a : node.getModifiers().getAnnotations()) emit(a, ANNOTATIONS.get(resolved(a.getAnnotationType())));
                        for (Tree type : node.getImplementsClause()) emit(type, TYPES.get(resolved(type)));
                        emit(node.getExtendsClause(), TYPES.get(resolved(node.getExtendsClause())));
                        return super.visitClass(node, unused);
                    }
                    @Override public Void visitMethod(MethodTree node, Void unused) {
                        for (AnnotationTree a : node.getModifiers().getAnnotations()) emit(a, ANNOTATIONS.get(resolved(a.getAnnotationType())));
                        emit(node, TYPES.get(resolved(node.getReturnType())));
                        return super.visitMethod(node, unused);
                    }
                    @Override public Void visitVariable(VariableTree node, Void unused) {
                        emit(node, TYPES.get(resolved(node.getType())));
                        return super.visitVariable(node, unused);
                    }
                    @Override public Void visitCatch(CatchTree node, Void unused) {
                        emit(node, new Rule("Contains an explicit exception catch block.", "ERROR_HANDLING"));
                        return super.visitCatch(node, unused);
                    }
                }.scan(unit, null);
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean knownAncestors(javax.lang.model.element.TypeElement type, Set<javax.lang.model.element.TypeElement> visited) {
        if (!visited.add(type)) return true;
        List<javax.lang.model.type.TypeMirror> parents=new ArrayList<>(type.getInterfaces());
        parents.add(type.getSuperclass());
        for (var parent:parents) {
            if(parent.getKind()==javax.lang.model.type.TypeKind.ERROR) return false;
            if(parent instanceof javax.lang.model.type.DeclaredType declared && declared.asElement() instanceof javax.lang.model.element.TypeElement ancestor &&
                    !knownAncestors(ancestor,visited)) return false;
        }
        return true;
    }

    // Resolve only installed, trusted framework class files; never fetch a repository's dependencies.
    private JavaFileManager trustedSymbols(StandardJavaFileManager delegate) {
        ClassLoader loader = StaticEvidenceAnalyzer.class.getClassLoader();
        class Binary extends SimpleJavaFileObject {
            final String name;
            Binary(String name) { super(URI.create("trusted:///" + name.replace('.', '/') + ".class"), Kind.CLASS); this.name = name; }
            @Override public java.io.InputStream openInputStream() throws java.io.IOException {
                var input = loader.getResourceAsStream(name.replace('.', '/') + ".class");
                if (input == null) throw new java.io.FileNotFoundException();
                return input;
            }
        }
        return new ForwardingJavaFileManager<StandardJavaFileManager>(delegate) {
            @Override public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws java.io.IOException {
                List<JavaFileObject> result = new ArrayList<>();
                super.list(location, packageName, kinds, recurse).forEach(result::add);
                if (location != StandardLocation.CLASS_PATH || !kinds.contains(JavaFileObject.Kind.CLASS) ||
                        !(packageName.startsWith("org.springframework.") || packageName.equals("jakarta.persistence"))) return result;
                String prefix = packageName.replace('.', '/') + "/";
                Set<String> names = new TreeSet<>();
                var resources = loader.getResources(prefix.substring(0, prefix.length() - 1));
                while (resources.hasMoreElements()) {
                    var url = resources.nextElement();
                    if (url.openConnection() instanceof java.net.JarURLConnection jar) {
                        var entries = jar.getJarFile().entries();
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            if (name.startsWith(prefix) && name.endsWith(".class") && name.indexOf('/', prefix.length()) < 0)
                                names.add(name.substring(0, name.length() - 6).replace('/', '.'));
                        }
                    }
                }
                for (String name : names) result.add(new Binary(name));
                return result;
            }
            @Override public String inferBinaryName(Location location, JavaFileObject file) {
                return file instanceof Binary binary ? binary.name : super.inferBinaryName(location, file);
            }
            @Override public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind, FileObject sibling) {
                throw new IllegalStateException("Source output is forbidden");
            }
        };
    }

    private void dependency(Member member, TextFile file, List<Evidence> evidence) {
        if (evidence.size() >= 200) return;
        Set<String> technologies = new TreeSet<>();
        if (file.path().endsWith("pom.xml")) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
                var builder = factory.newDocumentBuilder(); builder.setErrorHandler(new DefaultHandler());
                var doc = builder.parse(new InputSource(new StringReader(file.text())));
                var dependencies = doc.getElementsByTagName("dependency");
                for (int i = 0; i < dependencies.getLength(); i++) {
                    var element = (org.w3c.dom.Element) dependencies.item(i);
                    var groups = element.getElementsByTagName("groupId");
                    if (groups.getLength() == 1) technology(groups.item(0).getTextContent().trim(), technologies);
                }
            } catch (Exception ignored) { return; }
        } else {
            // Only literal dependency calls. Strings and comments containing sample calls are never scanned as code.
            List<String> tokens = gradleTokens(file.text());
            for (int i = 0; i + 1 < tokens.size(); i++) {
                if (!Set.of("implementation", "api", "compileOnly", "runtimeOnly", "testImplementation").contains(tokens.get(i))) continue;
                int argument = i + 1;
                if (tokens.get(argument).equals("(")) argument++;
                if (argument < tokens.size() && tokens.get(argument).startsWith("\"")) {
                    String value = tokens.get(argument).substring(1);
                    if (value.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+"))
                        technology(value.substring(0, value.indexOf(':')), technologies);
                }
            }
        }
        for (String technology : technologies) if (evidence.size() < 200)
            evidence.add(new Evidence(UUID.randomUUID(), member.id(), file.path(), 1, file.text().split("\n", -1).length, ClaimType.CODE_FACT,
                    "Declares a " + technology + " build dependency; runtime use is not established by this declaration."));
    }
    private void technology(String group, Set<String> technologies) {
        if (group.equals("org.springframework.boot")) technologies.add("Spring Boot");
        else if (group.startsWith("org.springframework")) technologies.add("Spring");
        else if (group.equals("org.hibernate.orm")) technologies.add("Hibernate");
        else if (group.equals("org.postgresql")) technologies.add("PostgreSQL");
    }
    private List<String> gradleTokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (text.startsWith("//", i)) { int end = text.indexOf('\n', i); i = end < 0 ? text.length() : end; }
            else if (text.startsWith("/*", i)) { int end = text.indexOf("*/", i + 2); i = end < 0 ? text.length() : end + 2; }
            // shortcut: skip files with slashy or multiline literals until a full Gradle parser is justified.
            else if (c == '/' || text.startsWith("\"\"\"", i) || text.startsWith("'''", i)) return List.of();
            else if (c == '"' || c == '\'') {
                char quote = c; StringBuilder value = new StringBuilder(); i++;
                while (i < text.length() && text.charAt(i) != quote) {
                    if (text.charAt(i) == '\\') { value.append('?'); i = Math.min(text.length(), i + 2); }
                    else value.append(text.charAt(i++));
                }
                if (i < text.length()) i++;
                tokens.add("\"" + value);
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i++; while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) i++;
                tokens.add(text.substring(start, i));
            } else { if (!Character.isWhitespace(c)) tokens.add(Character.toString(c)); i++; }
        }
        return tokens;
    }
}
