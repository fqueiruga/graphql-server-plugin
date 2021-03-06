package io.jenkins.plugins.graphql;

import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import graphql.Scalars;
import graphql.schema.*;
import graphql.schema.idl.*;
import hudson.DescriptorExtensionList;
import hudson.model.*;
import io.jenkins.plugins.graphql.types.AdditionalScalarTypes;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.export.Model;
import org.kohsuke.stapler.export.ModelBuilder;
import org.kohsuke.stapler.export.Property;
import org.kohsuke.stapler.export.TypeUtil;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Builders {
    private static final Logger LOGGER = Logger.getLogger(Builders.class.getName());
    private static final ModelBuilder MODEL_BUILDER = new ModelBuilder();

    private static final String ARG_OFFSET = "offset";
    private static final String ARG_LIMIT = "limit";
    private static final String ARG_TYPE = "type";
    private static final String ARG_ID = "id";

    private static String makeClassIdDefintion(final Class<?> clazz) {
        final Method idMethod = IdFinder.idMethod(clazz);
        if (idMethod == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("  \"UniqueID\"\n");
        sb.append("  id: ID\n");
        return sb.toString();
        // .dataFetcher(dataFetcher -> idMethod.invoke(dataFetcher.getSource()))
    }

    private static Iterator<?> slice(final Iterable<?> base, final int start, final int limit) {
        return Iterators.limit(Iterables.skip(base, start).iterator(), limit);
    }

    private static final HashMap<String, GraphQLScalarType> javaTypesToGraphqlTypes = new HashMap<>();

    static {
        javaTypesToGraphqlTypes.put("ID", Scalars.GraphQLID);

        javaTypesToGraphqlTypes.put("boolean", Scalars.GraphQLBoolean);
        javaTypesToGraphqlTypes.put(Boolean.class.getSimpleName(), Scalars.GraphQLBoolean);

        javaTypesToGraphqlTypes.put("char", Scalars.GraphQLBoolean);
        javaTypesToGraphqlTypes.put(Character.class.getSimpleName(), Scalars.GraphQLChar);

        javaTypesToGraphqlTypes.put("byte", Scalars.GraphQLByte);
        javaTypesToGraphqlTypes.put(Byte.class.getSimpleName(), Scalars.GraphQLByte);

        javaTypesToGraphqlTypes.put("string", Scalars.GraphQLString);
        javaTypesToGraphqlTypes.put(String.class.getSimpleName(), Scalars.GraphQLString);

        javaTypesToGraphqlTypes.put("float", Scalars.GraphQLFloat);
        javaTypesToGraphqlTypes.put(Float.class.getSimpleName(), Scalars.GraphQLFloat);

        javaTypesToGraphqlTypes.put("integer", Scalars.GraphQLInt);
        javaTypesToGraphqlTypes.put("int", Scalars.GraphQLInt);
        javaTypesToGraphqlTypes.put(Integer.class.getSimpleName(), Scalars.GraphQLInt);

        javaTypesToGraphqlTypes.put("long", Scalars.GraphQLLong);
        javaTypesToGraphqlTypes.put(Long.class.getSimpleName(), Scalars.GraphQLLong);

        javaTypesToGraphqlTypes.put("double", Scalars.GraphQLLong);
        javaTypesToGraphqlTypes.put(Double.class.getSimpleName(), Scalars.GraphQLBigDecimal);

        javaTypesToGraphqlTypes.put("short", Scalars.GraphQLShort);
        javaTypesToGraphqlTypes.put(Short.class.getSimpleName(), Scalars.GraphQLShort);

        javaTypesToGraphqlTypes.put("GregorianCalendar", AdditionalScalarTypes.GregrianCalendarScalar);
        javaTypesToGraphqlTypes.put("Calendar", AdditionalScalarTypes.GregrianCalendarScalar);
        javaTypesToGraphqlTypes.put("Date", AdditionalScalarTypes.GregrianCalendarScalar);

    }

    /*** DONE STATIC */
    private HashMap<Class, String> graphQLTypes = new HashMap();
    private HashMap<String, Property> propertyMap = new HashMap<>();
    private PriorityQueue<Class> classQueue = new PriorityQueue<>(11, Comparator.comparing(Class::getName));
    private List<Class> extraTopLevelClasses = new ArrayList<>();

    protected String createSchemaClassName(final Class clazz) {
        assert (clazz != null);

        if (javaTypesToGraphqlTypes.containsKey(clazz.getSimpleName())) {
            return javaTypesToGraphqlTypes.get(clazz.getSimpleName()).getName();
        }

        final boolean isInterface = isInterfaceOrAbstract(clazz);

        // interfaces are never exported, so handle them seperately
        if (isInterface) {
            classQueue.addAll(ClassUtils.findSubclasses(MODEL_BUILDER, clazz));
        } else {
            try {
                MODEL_BUILDER.get(clazz);
            } catch (final org.kohsuke.stapler.export.NotExportableException e) {
                return Scalars.GraphQLString.getName();
            }
        }
        classQueue.add(clazz);
        return ClassUtils.getGraphQLClassName(clazz);
    }

    private boolean isInterfaceOrAbstract(Class clazz) {
        return Modifier.isInterface(clazz.getModifiers())
            || Modifier.isAbstract(clazz.getModifiers());
    }

    private Class getCollectionClass(final Property p) {
        return TypeUtil
                .erasure(TypeUtil.getTypeArgument(TypeUtil.getBaseClass(p.getGenericType(), Collection.class), 0));
    }

    private void buildSchemaFromClass(final Class<?> clazz) {
        if (graphQLTypes.containsKey(clazz)) {
            return;
        }

        if (shouldIgnoreClass(clazz)) {
            return;
        }

        boolean isInterface = isInterfaceOrAbstract(clazz);
        try {
            MODEL_BUILDER.get(clazz);
        } catch (final org.kohsuke.stapler.export.NotExportableException e) {
            isInterface = true;
        }

        graphQLTypes.put(clazz, buildGraphQLTypeFromModel(clazz, isInterface));
    }

    static boolean shouldIgnoreClass(final Class clazz) {
        return clazz.isAnnotationPresent(NoExternalUse.class) || clazz.isAnonymousClass();
    }

    @SuppressWarnings("squid:S135")
    String buildGraphQLTypeFromModel(final Class clazz, final boolean isInterface) {
        final Model<?> model = MODEL_BUILDER.getOrNull(clazz, (Class) null, (String) null);
        String containerTypeName = ClassUtils.getGraphQLClassName(clazz);

        final StringBuilder sb = new StringBuilder();

        final Jenkins instance = Jenkins.getInstanceOrNull();
        if (instance != null) {
            final Descriptor descriptor = instance.getDescriptor(clazz);
            if (descriptor != null) {
                // TODO - make test to check for display names having quotes in them
                sb.append("\"" + descriptor.getDisplayName().replaceAll("\"", "\\\\\"")  + "\"\n");
            }
        }

        if (isInterface) {
            sb.append("interface ");
        } else {
            sb.append("type ");
        }
        sb.append(containerTypeName);
        sb.append("%s {\n");
        sb.append("  \"Class Name\"\n");
        sb.append("  _class: String\n");
        sb.append(makeClassIdDefintion(clazz));

        if (model != null) {
            final ArrayList<Model<?>> queue = new ArrayList<>();
            queue.add(model);

            Model<?> superModel = model.superModel;
            while (superModel != null) {
                queue.add(superModel);
                superModel = superModel.superModel;
            }

            for (final Model<?> _model : queue) {
                for (final Property p : _model.getProperties()) {
                    if (this.propertyMap.containsKey(containerTypeName + "#" + p.name)) {
                        continue;
                    }
                    final Class propertyClazz = p.getType();

                    String className;
                    if ("id".equals(p.name)) {
                        continue; /// we handle it in a different way
                    } else if (propertyClazz.isArray()) {
                        className = "[" + createSchemaClassName(propertyClazz.getComponentType()) + "]";
                    } else if (Collection.class.isAssignableFrom(propertyClazz)) {
                        className = "[" + createSchemaClassName(getCollectionClass(p)) + "]";
                    } else {
                        className = createSchemaClassName(propertyClazz);
                    }

                    // .dataFetcher(dataFetchingEnvironment ->
                    // p.getValue(dataFetchingEnvironment.getSource()));

                    if (StringUtils.isNotEmpty(p.getJavadoc())) {
                        sb.append("  \"\"\"\n");
                        // indent with 2 spaces
                        sb.append(p.getJavadoc().replaceAll("(?m)^", "  "));
                        sb.append("\n  \"\"\"\n");
                    }
                    sb.append("  ");
                    sb.append(p.name);
                    sb.append(": ");
                    sb.append(className);
                    if (propertyClazz.isAnnotationPresent(Nonnull.class)) {
                        sb.append("!");
                    }
                    sb.append("\n");

                    /*
                     * if (className instanceof GraphQLList) {
                     * fieldBuilder.dataFetcher(dataFetchingEnvironment -> { final int offset =
                     * dataFetchingEnvironment.<Integer>getArgument(ARG_OFFSET); final int limit =
                     * dataFetchingEnvironment.<Integer>getArgument(ARG_LIMIT); final String id =
                     * dataFetchingEnvironment.getArgument(ARG_ID);
                     *
                     * List<?> valuesList; final Object values =
                     * p.getValue(dataFetchingEnvironment.getSource()); if (values instanceof List)
                     * { valuesList = ((List<?>) values); } else { valuesList =
                     * Arrays.asList((Object[]) values);
                     *
                     * } if (id != null && !id.isEmpty()) { for (final Object value : valuesList) {
                     * final Method method = IdFinder.idMethod(value.getClass()); if (method ==
                     * null) { continue; }
                     *
                     * final String objectId = String.valueOf(method.invoke(value)); if
                     * (id.equals(objectId)) { return Stream.of(value)
                     * .filter(StreamUtils::isAllowed) .toArray(); } } return null; }
                     *
                     * return Lists.newArrayList(slice(valuesList, offset, limit)) .stream()
                     * .filter(StreamUtils::isAllowed) .toArray(); });
                     * fieldBuilder.argument(GraphQLArgument.newArgument() .name(ARG_OFFSET)
                     * .type(Scalars.GraphQLInt) .defaultValue(0) )
                     * .argument(GraphQLArgument.newArgument() .name(ARG_LIMIT)
                     * .type(Scalars.GraphQLInt) .defaultValue(100) )
                     * .argument(GraphQLArgument.newArgument() .name(ARG_TYPE)
                     * .type(Scalars.GraphQLString) ) .argument(GraphQLArgument.newArgument()
                     * .name(ARG_ID) .type(Scalars.GraphQLID) ); }
                     */
                    propertyMap.put(containerTypeName + "#" + p.name, p);
                }
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String getFieldNameForRootAction(RootAction action) {
        if (action == null) { return ""; }
        String urlName = action.getUrlName();
        if (urlName == null) { return ""; }
        return urlName.replaceAll("-", "_").replaceAll("/", "_");
    }

    @SuppressWarnings("rawtypes")
    public GraphQLSchema buildSchema() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) { return null; }
//        Pattern typeToInterface = Pattern.compile("^(?:type|interface) ([a-zA-Z0-9_]+)\\s*%s", Pattern.MULTILINE);

        List<RootAction> rootActions = DescriptorExtensionList
            .lookup(RootAction.class)
            .stream()
            // check to see if its exported
            .filter(action -> MODEL_BUILDER.getOrNull(action.getClass(), null, null) != null)
            // check to see if it has a display name
            .filter(action -> action.getDisplayName() != null)
            // finally check to see if its restricted at all (Shouldn't be if exported)
            .filter(action -> !Arrays.asList(action.getClass().getAnnotations()).contains(Restricted.class))
            .collect(Collectors.toList());
        for (RootAction action : rootActions) {
            classQueue.add(action.getClass());
        }
        classQueue.addAll(
            DescriptorExtensionList
                .lookup(Descriptor.class)
                .stream()
//                .map(i -> DescriptorExtensionList.lookup(i.getClass()))
//                .flatMap(Collection::stream)
                .map(i -> i.getKlass().toJavaClass())
                .collect(Collectors.toList())
        );
        classQueue.addAll(
            j.getExtensionList(Action.class)
                .stream()
                .map(i -> i.getClass())
                .collect(Collectors.toList())
        );
        classQueue.add(AbstractItem.class);
        classQueue.add(Job.class);
        classQueue.add(User.class);
        classQueue.addAll(this.extraTopLevelClasses);

        while (!classQueue.isEmpty()) {
            final Class clazz = classQueue.poll();
            if (clazz == Object.class || clazz == Class.class) {
                continue;
            }
            this.buildSchemaFromClass(clazz);
        }

        StringBuilder sb = new StringBuilder();
        for (GraphQLScalarType type : new HashSet<GraphQLScalarType>(javaTypesToGraphqlTypes.values())) {
            sb.append("scalar " + type.getName() + "\n");
        }

        sb.append("\n");

        Set<String> graphQLTypeStrings = new HashSet();
        for (Map.Entry<Class, String> graphqlEntry : this.graphQLTypes.entrySet()) {
            Class<?> interfaceClazz = graphqlEntry.getKey();
            List<String> interfaces = new LinkedList<>();
            for (Map.Entry<Class, String> entry1 : this.graphQLTypes.entrySet()) {
                Class<?> instanceClazz = entry1.getKey();
                if (interfaceClazz == instanceClazz) {
                    continue;
                }
                if (!isInterfaceOrAbstract(instanceClazz)) {
                    continue;
                }
                if (instanceClazz.isAssignableFrom(interfaceClazz)) {
                    interfaces.add(ClassUtils.getGraphQLClassName(instanceClazz));
                }
            }
            if (interfaces.size() > 0) {
                graphQLTypeStrings.add(
                    String.format(
                        this.graphQLTypes.get(interfaceClazz),
                        " implements " + String.join(" & ", interfaces)
                    )
                );
            } else {
                graphQLTypeStrings.add(
                    String.format(this.graphQLTypes.get(interfaceClazz), "")
                );
            }
        }

        sb.append(
            graphQLTypeStrings.stream()
                .map( Object::toString )
                .collect( Collectors.joining( "\n\n" ) )
        );

        sb.append("\n");
        sb.append("schema {\n");
        sb.append("  query: QueryType\n");
        sb.append("}\n");

        sb.append("type QueryType {\n");
        sb.append("  allItems(offset: Int = 0, limit: Int = 100, Type: String, id: ID): [" + ClassUtils.getGraphQLClassName(AbstractItem.class) + "]\n");
        sb.append("  allUsers(offset: Int = 0, limit: Int = 100, Type: String, id: ID): [" + ClassUtils.getGraphQLClassName(User.class)+ "]\n");
        for (RootAction action : rootActions) {
            sb.append("  " + getFieldNameForRootAction(action) + ": " + ClassUtils.getGraphQLClassName(action.getClass()) + "\n");
        }
        sb.append("}\n");

        // try {
        //     Files.write(sb.toString().getBytes(), Paths.get("./schema.graphql").toFile());
        // } catch (IOException e) {
        //     // TODO Auto-generated catch block
        //     e.printStackTrace();
        // }

        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(sb.toString());
        RuntimeWiring.Builder runtimeWiring = RuntimeWiring.newRuntimeWiring();
        runtimeWiring.type("QueryType", new UnaryOperator<TypeRuntimeWiring.Builder>() {
            @Override
            public TypeRuntimeWiring.Builder apply(TypeRuntimeWiring.Builder builder) {
                builder.dataFetcher("allItems", getObjectDataFetcher(AbstractItem.class));
                builder.dataFetcher("allUsers", getObjectDataFetcher(User.class));
                for (RootAction action : rootActions) {
                    builder.dataFetcher(getFieldNameForRootAction(action), new StaticDataFetcher(action));
                }
                return builder;
            }
        });
        runtimeWiring.wiringFactory(new JenkinsWireingFactory(javaTypesToGraphqlTypes, propertyMap));
        SchemaGenerator schemaGenerator = new SchemaGenerator();

        this.graphQLTypes = null;
        this.classQueue = null;
        this.extraTopLevelClasses = null;

        return schemaGenerator.makeExecutableSchema(typeRegistry, runtimeWiring.build());
    }

    private DataFetcher<Object> getObjectDataFetcher(Class<?> defaultClazz) {
        return dataFetchingEnvironment -> {
            Class clazz = defaultClazz;
            final Jenkins instance = Jenkins.getInstanceOrNull();
            final int offset = (int) dataFetchingEnvironment.getArguments().getOrDefault(ARG_OFFSET, 0);
            final int limit = (int) dataFetchingEnvironment.getArguments().getOrDefault(ARG_LIMIT, 100);
            final String clazzName = (String) dataFetchingEnvironment.getArguments().getOrDefault(ARG_TYPE, "");
            final String id = (String) dataFetchingEnvironment.getArguments().getOrDefault(ARG_ID, null);

            if (clazzName != null && !clazzName.isEmpty()) {
                clazz = Class.forName(clazzName);
            }

            Iterable iterable;
            if (clazz == User.class) {
                if (id != null && !id.isEmpty()) {
                    return Stream.of(User.get(id, false, Collections.emptyMap()))
                        .filter(Objects::nonNull)
                        .filter(StreamUtils::isAllowed)
                        .toArray();
                }
                iterable = User.getAll();
            } else {
                if (id != null && !id.isEmpty()) {
                    if (instance == null) {
                        LOGGER.log(Level.SEVERE, "Jenkins.getInstanceOrNull() is null, panic panic die die");
                        return null;
                    }
                    return Stream.of(instance.getItemByFullName(id))
                        .filter(Objects::nonNull)
                        .filter(StreamUtils::isAllowed)
                        .toArray();
                }

                iterable = Items.allItems(
                    Jenkins.getAuthentication(),
                    Jenkins.getInstanceOrNull(),
                    clazz
                );
            }
            return Lists.newArrayList(slice(iterable, offset, limit))
                .stream()
                .filter(StreamUtils::isAllowed)
                .toArray();
        };
    }

    public void addExtraTopLevelClasses(final List<Class> clazzes) {
        this.extraTopLevelClasses.addAll(clazzes);
    }
}
