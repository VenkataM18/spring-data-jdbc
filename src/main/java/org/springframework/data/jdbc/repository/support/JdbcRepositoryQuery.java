/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jdbc.repository.support;

import java.lang.reflect.Constructor;
import java.sql.JDBCType;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.jdbc.core.convert.JdbcColumnTypes;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.jdbc.core.convert.JdbcValue;
import org.springframework.data.jdbc.support.JdbcUtil;
import org.springframework.data.mapping.callback.EntityCallbacks;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.event.AfterLoadCallback;
import org.springframework.data.relational.core.mapping.event.AfterLoadEvent;
import org.springframework.data.relational.core.mapping.event.Identifier;
import org.springframework.data.repository.query.Parameter;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * A query to be executed based on a repository method, it's annotated SQL query and the arguments provided to the
 * method.
 *
 * @author Jens Schauder
 * @author Kazuki Shimizu
 * @author Oliver Gierke
 * @author Maciej Walkowiak
 */
class JdbcRepositoryQuery implements RepositoryQuery {

	private static final String PARAMETER_NEEDS_TO_BE_NAMED = "For queries with named parameters you need to provide names for method parameters. Use @Param for query method parameters, or when on Java 8+ use the javac flag -parameters.";

	private final ApplicationEventPublisher publisher;
	private final EntityCallbacks callbacks;
	private final RelationalMappingContext context;
	private final JdbcQueryMethod queryMethod;
	private final NamedParameterJdbcOperations operations;
	private final QueryExecutor<Object> executor;
	private final JdbcConverter converter;

	/**
	 * Creates a new {@link JdbcRepositoryQuery} for the given {@link JdbcQueryMethod}, {@link RelationalMappingContext}
	 * and {@link RowMapper}.
	 *
	 * @param publisher must not be {@literal null}.
	 * @param context must not be {@literal null}.
	 * @param queryMethod must not be {@literal null}.
	 * @param operations must not be {@literal null}.
	 * @param defaultRowMapper can be {@literal null} (only in case of a modifying query).
	 */
	JdbcRepositoryQuery(ApplicationEventPublisher publisher, @Nullable EntityCallbacks callbacks,
			RelationalMappingContext context, JdbcQueryMethod queryMethod, NamedParameterJdbcOperations operations,
			RowMapper<?> defaultRowMapper, JdbcConverter converter) {

		Assert.notNull(publisher, "Publisher must not be null!");
		Assert.notNull(context, "Context must not be null!");
		Assert.notNull(queryMethod, "Query method must not be null!");
		Assert.notNull(operations, "NamedParameterJdbcOperations must not be null!");

		if (!queryMethod.isModifyingQuery()) {
			Assert.notNull(defaultRowMapper, "Mapper must not be null!");
		}

		this.publisher = publisher;
		this.callbacks = callbacks == null ? EntityCallbacks.create() : callbacks;
		this.context = context;
		this.queryMethod = queryMethod;
		this.operations = operations;

		RowMapper<Object> rowMapper = determineRowMapper(defaultRowMapper);
		executor = createExecutor( //
				queryMethod, //
				determineResultSetExtractor(rowMapper != defaultRowMapper ? rowMapper : null), //
				rowMapper //
		);

		this.converter = converter;
	}

	private QueryExecutor<Object> createExecutor(JdbcQueryMethod queryMethod,
			@Nullable ResultSetExtractor<Object> extractor, RowMapper<Object> rowMapper) {

		String query = determineQuery();

		if (queryMethod.isModifyingQuery()) {
			return createModifyingQueryExecutor(query);
		}
		if (queryMethod.isCollectionQuery() || queryMethod.isStreamQuery()) {
			QueryExecutor<Object> innerExecutor = extractor != null ? createResultSetExtractorQueryExecutor(query, extractor)
					: createListRowMapperQueryExecutor(query, rowMapper);
			return createCollectionQueryExecutor(innerExecutor);
		}

		QueryExecutor<Object> innerExecutor = extractor != null ? createResultSetExtractorQueryExecutor(query, extractor)
				: createObjectRowMapperQueryExecutor(query, rowMapper);
		return createObjectQueryExecutor(innerExecutor);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.RepositoryQuery#execute(java.lang.Object[])
	 */
	@Override
	public Object execute(Object[] objects) {

		return executor.execute(bindParameters(objects));
	}

	private QueryExecutor<Object> createObjectQueryExecutor(QueryExecutor<Object> executor) {

		return parameters -> {

			try {

				Object result = executor.execute(parameters);

				publishAfterLoad(result);

				return result;

			} catch (EmptyResultDataAccessException e) {
				return null;
			}
		};
	}

	private QueryExecutor<Object> createCollectionQueryExecutor(QueryExecutor<Object> executor) {

		return parameters -> {

			List<?> result = (List<?>) executor.execute(parameters);

			Assert.notNull(result, "A collection valued result must never be null.");

			publishAfterLoad(result);

			return result;
		};
	}

	private QueryExecutor<Object> createModifyingQueryExecutor(String query) {

		return parameters -> {

			int updatedCount = operations.update(query, parameters);
			Class<?> returnedObjectType = queryMethod.getReturnedObjectType();

			return (returnedObjectType == boolean.class || returnedObjectType == Boolean.class) ? updatedCount != 0
					: updatedCount;
		};
	}

	private QueryExecutor<Object> createListRowMapperQueryExecutor(String query, RowMapper<?> rowMapper) {
		return parameters -> operations.query(query, parameters, rowMapper);
	}

	private QueryExecutor<Object> createObjectRowMapperQueryExecutor(String query, RowMapper<?> rowMapper) {
		return parameters -> operations.queryForObject(query, parameters, rowMapper);
	}

	private QueryExecutor<Object> createResultSetExtractorQueryExecutor(String query,
			ResultSetExtractor<?> resultSetExtractor) {
		return parameters -> operations.query(query, parameters, resultSetExtractor);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.RepositoryQuery#getQueryMethod()
	 */
	@Override
	public JdbcQueryMethod getQueryMethod() {
		return queryMethod;
	}

	private String determineQuery() {

		String query = queryMethod.getDeclaredQuery();

		if (StringUtils.isEmpty(query)) {
			throw new IllegalStateException(String.format("No query specified on %s", queryMethod.getName()));
		}

		return query;
	}

	private MapSqlParameterSource bindParameters(Object[] objects) {

		MapSqlParameterSource parameters = new MapSqlParameterSource();

		queryMethod.getParameters().getBindableParameters().forEach(p -> {

			convertAndAddParameter(parameters, p, objects[p.getIndex()]);
		});

		return parameters;
	}

	private void convertAndAddParameter(MapSqlParameterSource parameters, Parameter p, Object value) {

		String parameterName = p.getName().orElseThrow(() -> new IllegalStateException(PARAMETER_NEEDS_TO_BE_NAMED));

		Class<?> parameterType = queryMethod.getParameters().getParameter(p.getIndex()).getType();
		Class<?> conversionTargetType = JdbcColumnTypes.INSTANCE.resolvePrimitiveType(parameterType);

		JdbcValue jdbcValue = converter.writeJdbcValue(value, conversionTargetType,
				JdbcUtil.sqlTypeFor(conversionTargetType));

		JDBCType jdbcType = jdbcValue.getJdbcType();
		if (jdbcType == null) {

			parameters.addValue(parameterName, jdbcValue.getValue());
		} else {
			parameters.addValue(parameterName, jdbcValue.getValue(), jdbcType.getVendorTypeNumber());
		}
	}

	@Nullable
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private ResultSetExtractor<Object> determineResultSetExtractor(@Nullable RowMapper<Object> rowMapper) {

		Class<? extends ResultSetExtractor> resultSetExtractorClass = queryMethod.getResultSetExtractorClass();

		if (isUnconfigured(resultSetExtractorClass, ResultSetExtractor.class)) {
			return null;
		}

		Constructor<? extends ResultSetExtractor> constructor = ClassUtils
				.getConstructorIfAvailable(resultSetExtractorClass, RowMapper.class);

		if (constructor != null) {
			return BeanUtils.instantiateClass(constructor, rowMapper);
		}

		return BeanUtils.instantiateClass(resultSetExtractorClass);
	}

	@SuppressWarnings("unchecked")
	private RowMapper<Object> determineRowMapper(RowMapper<?> defaultMapper) {

		Class<?> rowMapperClass = queryMethod.getRowMapperClass();

		if (isUnconfigured(rowMapperClass, RowMapper.class)) {
			return (RowMapper<Object>) defaultMapper;
		}

		return (RowMapper<Object>) BeanUtils.instantiateClass(rowMapperClass);
	}

	private static boolean isUnconfigured(@Nullable Class<?> configuredClass, Class<?> defaultClass) {
		return configuredClass == null || configuredClass == defaultClass;
	}

	private <T> void publishAfterLoad(Iterable<T> all) {

		for (T e : all) {
			publishAfterLoad(e);
		}
	}

	private <T> void publishAfterLoad(@Nullable T entity) {

		if (entity != null && context.hasPersistentEntityFor(entity.getClass())) {

			RelationalPersistentEntity<?> e = context.getRequiredPersistentEntity(entity.getClass());
			Object identifier = e.getIdentifierAccessor(entity).getIdentifier();

			if (identifier != null) {
				publisher.publishEvent(new AfterLoadEvent(Identifier.of(identifier), entity));
			}

			callbacks.callback(AfterLoadCallback.class, entity);
		}
	}

	private interface QueryExecutor<T> {
		@Nullable
		T execute(MapSqlParameterSource parameter);
	}
}
