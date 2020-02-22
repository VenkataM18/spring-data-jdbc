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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.util.Properties;

import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.repository.core.NamedQueries;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.PropertiesBasedNamedQueries;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link JdbcQueryMethod}.
 *
 * @author Jens Schauder
 * @author Oliver Gierke
 * @author Moises Cisneros
 */
public class JdbcQueryMethodUnitTests {

	public static final String QUERY_NAME = "DUMMY.SELECT";
	public static final String QUERY = "SELECT something";
	public static final String METHOD_WITHOUT_QUERY_ANNOTATION = "methodWithImplicitlyNamedQuery";
	public static final String QUERY2 = "SELECT something NAME AND VALUE";

	NamedQueries namedQueries;
	RepositoryMetadata metadata;

	@Before
	public void before() {

		Properties properties = new Properties();
		properties.setProperty(QUERY_NAME, QUERY);
		// String is used as domain class because the methods used for testing aren't part of a repository and therefore the
		// return type is used as the domain type.
		properties.setProperty("String." + METHOD_WITHOUT_QUERY_ANNOTATION, QUERY2);
		namedQueries = new PropertiesBasedNamedQueries(properties);

		metadata = mock(RepositoryMetadata.class);
		doReturn(String.class).when(metadata).getReturnedDomainClass(any(Method.class));
	}

	@Test // DATAJDBC-165
	public void returnsSqlStatement() throws NoSuchMethodException {

		JdbcQueryMethod queryMethod = createJdbcQueryMethod("queryMethod");

		assertThat(queryMethod.getDeclaredQuery()).isEqualTo(QUERY);
	}

	@Test // DATAJDBC-165
	public void returnsSpecifiedRowMapperClass() throws NoSuchMethodException {

		JdbcQueryMethod queryMethod = createJdbcQueryMethod("queryMethod");

		assertThat(queryMethod.getRowMapperClass()).isEqualTo(CustomRowMapper.class);
	}

	@Test // DATAJDBC-234
	public void returnsSqlStatementName() throws NoSuchMethodException {

		JdbcQueryMethod queryMethod = createJdbcQueryMethod("queryMethodName");
		assertThat(queryMethod.getDeclaredQuery()).isEqualTo(QUERY);

	}

	@Test // DATAJDBC-234
	public void returnsSpecifiedSqlStatementIfNameAndValueAreGiven() throws NoSuchMethodException {

		JdbcQueryMethod queryMethod = createJdbcQueryMethod("queryMethodWithNameAndValue");
		assertThat(queryMethod.getDeclaredQuery()).isEqualTo(QUERY2);

	}

	@NotNull
	private JdbcQueryMethod createJdbcQueryMethod(String methodName) throws NoSuchMethodException {

		Method method = JdbcQueryMethodUnitTests.class.getDeclaredMethod(methodName);
		return new JdbcQueryMethod(method, metadata, mock(ProjectionFactory.class), namedQueries);
	}

	@Test // DATAJDBC-234
	public void returnsImplicitlyNamedQuery() throws NoSuchMethodException {

		JdbcQueryMethod queryMethod = createJdbcQueryMethod("methodWithImplicitlyNamedQuery");
		assertThat(queryMethod.getDeclaredQuery()).isEqualTo(QUERY2);
	}

	@Test // DATAJDBC-234
	public void returnsNullIfNoQueryIsFound() throws NoSuchMethodException {

		JdbcQueryMethod queryMethod = createJdbcQueryMethod("methodWithoutAnyQuery");
		assertThat(queryMethod.getDeclaredQuery()).isEqualTo(null);
	}

	@Query(value = QUERY, rowMapperClass = CustomRowMapper.class)
	private void queryMethod() {}

	@Query(name = QUERY_NAME)
	private void queryMethodName() {}

	@Query(value = QUERY2, name = QUERY_NAME)
	private void queryMethodWithNameAndValue() {}

	private void methodWithImplicitlyNamedQuery() {}

	private void methodWithoutAnyQuery() {}

	private class CustomRowMapper implements RowMapper<Object> {

		@Override
		public Object mapRow(ResultSet rs, int rowNum) {
			return null;
		}
	}
}