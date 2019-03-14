/*
 * Copyright 2011-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mongodb.repository.support;

import com.querydsl.core.types.Ops;
import com.querydsl.core.types.PredicateOperation;
import com.querydsl.core.types.dsl.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.Person.Sex;
import org.springframework.data.mongodb.repository.QAddress;
import org.springframework.data.mongodb.repository.QPerson;

import java.util.Collections;

import static com.querydsl.core.types.ExpressionUtils.path;
import static com.querydsl.core.types.ExpressionUtils.predicate;
import static com.querydsl.core.types.dsl.Expressions.constant;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

/**
 * Unit tests for {@link SpringDataMongodbSerializer}.
 *
 * @author Oliver Gierke
 * @author Christoph Strobl
 * @author Mark Paluch
 * @author Mikhail Kaduchka
 */
@RunWith(MockitoJUnitRunner.class)
public class SpringDataMongodbSerializerUnitTests {

	@Mock DbRefResolver dbFactory;
	MongoConverter converter;
	SpringDataMongodbSerializer serializer;

	@Before
	public void setUp() {

		MongoMappingContext context = new MongoMappingContext();

		this.converter = new MappingMongoConverter(dbFactory, context);
		this.serializer = new SpringDataMongodbSerializer(converter);
	}

	@Test
	public void uses_idAsKeyForIdProperty() {

		StringPath path = QPerson.person.id;
		assertThat(serializer.getKeyForPath(path, path.getMetadata()), is("_id"));
	}

	@Test
	public void buildsNestedKeyCorrectly() {

		StringPath path = QPerson.person.address.street;
		assertThat(serializer.getKeyForPath(path, path.getMetadata()), is("street"));
	}

	@Test
	public void convertsComplexObjectOnSerializing() {

		Address address = new Address();
		address.street = "Foo";
		address.zipCode = "01234";

		Document document = serializer.asDocument("foo", address);

		Object value = document.get("foo");
		assertThat(value, is(notNullValue()));
		assertThat(value, is(instanceOf(Document.class)));

		Object reference = converter.convertToMongoType(address);
		assertThat(value, is(reference));
	}

	@Test // DATAMONGO-376
	public void returnsEmptyStringIfNoPathExpressionIsGiven() {

		QAddress address = QPerson.person.shippingAddresses.any();
		assertThat(serializer.getKeyForPath(address, address.getMetadata()), is(""));
	}

	@Test // DATAMONGO-467, DATAMONGO-1798
	public void retainsIdPropertyType() {

		ObjectId id = new ObjectId();

		PathBuilder<Address> builder = new PathBuilder<Address>(Address.class, "address");
		StringPath idPath = builder.getString("id");

		Document result = (Document) serializer.visit((BooleanOperation) idPath.eq(id.toString()), null);
		assertThat(result.get("_id"), is(notNullValue()));
		assertThat(result.get("_id"), is(instanceOf(String.class)));
		assertThat(result.get("_id"), is(id.toString()));
	}

	@Test // DATAMONGO-761
	public void looksUpKeyForNonPropertyPath() {

		PathBuilder<Address> builder = new PathBuilder<Address>(Address.class, "address");
		SimplePath<Object> firstElementPath = builder.getArray("foo", String[].class).get(0);
		String path = serializer.getKeyForPath(firstElementPath, firstElementPath.getMetadata());

		assertThat(path, is("0"));
	}

	@Test // DATAMONGO-1485
	public void takesCustomConversionForEnumsIntoAccount() {

		MongoMappingContext context = new MongoMappingContext();

		MappingMongoConverter converter = new MappingMongoConverter(dbFactory, context);
		converter.setCustomConversions(new MongoCustomConversions(Collections.singletonList(new SexTypeWriteConverter())));
		converter.afterPropertiesSet();

		this.converter = converter;
		this.serializer = new SpringDataMongodbSerializer(this.converter);

		Object mappedPredicate = this.serializer.handle(QPerson.person.sex.eq(Sex.FEMALE));

		assertThat(mappedPredicate, is(instanceOf(Document.class)));
		assertThat(((Document) mappedPredicate).get("sex"), is("f"));
	}

	@Test // DATAMONGO-1848, DATAMONGO-1943
	public void shouldRemarshallListsAndDocuments() {

		BooleanExpression criteria = QPerson.person.lastname.isNotEmpty()
				.and(QPerson.person.firstname.containsIgnoreCase("foo")).not();

		assertThat(this.serializer.handle(criteria),
				is(equalTo(Document.parse("{ \"$or\" : [ { \"lastname\" : { \"$not\" : { "
						+ "\"$ne\" : \"\"}}} , { \"firstname\" : { \"$not\" : { \"$regex\" : \".*\\\\Qfoo\\\\E.*\" , \"$options\" : \"i\"}}}]}"))));
	}

    @Test // DATAMONGO-2228
    public void retainsOpsInAndExpression() {

        PredicateOperation testExpression = predicate(Ops.AND,
                predicate(Ops.OR,
					predicate(Ops.EQ, path(Object.class, "firstname"), constant("John")),
					predicate(Ops.EQ, path(Object.class, "firstname"), constant("Sarah"))),
				predicate(Ops.OR,
					predicate(Ops.EQ, path(Object.class, "lastname"), constant("Smith")),
					predicate(Ops.EQ, path(Object.class, "lastname"), constant("Connor")))
        );

        Document result = (Document) serializer.visit(testExpression, null);

        assertThat(result.toJson(), is("{\"$and\": [{\"$or\": [{\"firstname\": \"John\"}, {\"firstname\": \"Sarah\"}]}, {\"$or\": [{\"lastname\": \"Smith\"}, {\"lastname\": \"Connor\"}]}]}"));
    }

	class Address {
		String id;
		String street;
		@Field("zip_code") String zipCode;
		@Field("bar") String[] foo;
	}

	@WritingConverter
	public class SexTypeWriteConverter implements Converter<Sex, String> {

		@Override
		public String convert(Sex source) {

			if (source == null) {
				return null;
			}

			switch (source) {
				case MALE:
					return "m";
				case FEMALE:
					return "f";
				default:
					throw new IllegalArgumentException("o_O");
			}
		}
	}
}
