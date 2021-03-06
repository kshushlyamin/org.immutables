/*
    Copyright 2013-2014 Immutables Authors and Contributors

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.immutables.fixture.marshal;

import org.immutables.fixture.SillyStructure;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.google.common.base.CharMatcher;
import com.google.gson.reflect.TypeToken;
import de.undercouch.bson4jackson.BsonFactory;
import java.util.List;
import org.immutables.fixture.ImmutableHasNullable;
import org.immutables.fixture.ImmutableJsonIgnore;
import org.immutables.fixture.ImmutableSillySub1;
import org.immutables.fixture.ImmutableSillySub2;
import org.immutables.fixture.ImmutableSillySub3;
import org.immutables.fixture.JsonIgnore;
import org.immutables.fixture.SillyPolyHost;
import org.immutables.fixture.SillyPolyHost2;
import org.immutables.fixture.SillySubstructure;
import org.immutables.fixture.SillyTuplie;
import org.immutables.fixture.nested.ImmutableCadabra;
import org.immutables.fixture.nested.NonGrouped;
import org.junit.Test;
import static org.immutables.check.Checkers.*;

@SuppressWarnings("resource")
public class MarshallingTest {

  JsonFactory jsonFactory = new JsonFactory()
      .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
      .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
      .disable(JsonGenerator.Feature.QUOTE_FIELD_NAMES);

  JsonFactory strictierJsonFactory = new JsonFactory()
      .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
      .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);

  BsonFactory bsonFactory = new BsonFactory();

  @Test
  public void discoveredMarhaler() {
    SillySubstructure substructure =
        Marshaling.fromJson("{\"e1\":\"SOURCE\"}", SillySubstructure.class);
    check(substructure).not().isNull();
  }

  @Test
  public void unmarshalSingleton() {
    check(Marshaling.fromJson("[11]", NonGrouped.Cadabra.class)).same(ImmutableCadabra.of());
    check(Marshaling.fromJson("{\"x\": true}", NonGrouped.Cadabra.class)).same(ImmutableCadabra.of());
    check(Marshaling.fromJson("null", NonGrouped.Cadabra.class)).same(ImmutableCadabra.of());
  }

  @Test
  public void nullableMarshaling() {
    check(CharMatcher.WHITESPACE.removeFrom(Marshaling.toJson(ImmutableHasNullable.of())))
        .is("{}");
    check(Marshaling.fromJson("{}", ImmutableHasNullable.class)).is(ImmutableHasNullable.of());
    check(Marshaling.fromJson("{\"in\":1}", ImmutableHasNullable.class)).is(ImmutableHasNullable.of(1));
    check(Marshaling.fromJson("{\"def\":\"1\"}", ImmutableHasNullable.class)).is(ImmutableHasNullable.of().withDef("1"));
  }

  @Test
  public void jsonIgnore() {
    ImmutableJsonIgnore minimal = ImmutableJsonIgnore.of(1);
    ImmutableJsonIgnore expanded = minimal.withValues(1, 2);
    check(minimal).not().is(expanded);
    check(Marshaling.fromJson(Marshaling.toJson(expanded), JsonIgnore.class)).is(minimal);
  }

  @Test
  public void unmarshalingPolymorphicTypes() {
    SillyPolyHost host = Marshaling.fromJson("{ s:[{a:1},{b:'b'},{a:14}] }", SillyPolyHost.class);
    check(host.s()).isOf(
        ImmutableSillySub1.builder().a(1).build(),
        ImmutableSillySub2.builder().b("b").build(),
        ImmutableSillySub1.builder().a(14).build());

    SillyPolyHost2 s1 = Marshaling.fromJson("{s:{b:[1,2]}}", SillyPolyHost2.class);
    check(s1.s()).is(ImmutableSillySub3.builder().addB(1).addB(2).build());

    SillyPolyHost2 s2 = Marshaling.fromJson("{s:{b:'b'}}", SillyPolyHost2.class);
    check(s2.s()).is(ImmutableSillySub2.builder().b("b").build());
  }

  @Test
  public void marshalingPolymorphicTypesList() {
    SillyPolyHost h = Marshaling.fromJson("{s:[{a:1},{b:'b'},{a:14}]}", SillyPolyHost.class);
    check(Marshaling.fromJson(Marshaling.toJson(h), SillyPolyHost.class)).is(h);
  }

  @Test
  public void marshalingIterableMethods() {
    TypeToken<List<SillySubstructure>> m1 = new TypeToken<List<SillySubstructure>>() {};

    List<SillySubstructure> it = fromJsonIterable("[{e1:'SOURCE'},{e1:'CLASS'},{e1:'RUNTIME'}]", m1);

    check(fromJsonIterable(toJsonIterable(it, m1), m1)).is(it);
    TypeToken<List<SillyTuplie>> m2 = new TypeToken<List<SillyTuplie>>() {};

    List<SillyTuplie> tuplies = fromJsonIterable("[[1,null,[]],[2,null,[]]]", m2);
    check(fromJsonIterable(toJsonIterable(tuplies, m2), m2)).is(tuplies);
  }

  @Test
  public void marshalingPolymorphicOptionalTypes() {
    TypeToken<List<SillyPolyHost2>> m = new TypeToken<List<SillyPolyHost2>>() {};
    List<SillyPolyHost2> list = fromJsonIterable("[{s:{b:[1,2]},o:{b:'b'}}]", m);
    check(fromJsonIterable(toJsonIterable(list, m), m)).is(list);
    check(list.get(0).o()).isOf(ImmutableSillySub2.builder().b("b").build());
  }

  @Test
  public void marshalAndUnmarshalGeneratedType() {
    SillyStructure structure =
        Marshaling.fromJson("{attr1:'x', flag2:false,opt3:1, very4:33, wet5:555.55, subs6:null,"
            + " nest7:{ set2:'METHOD', set3: [1,2,4],floats4:[333.11] },"
            + "int9:0, tup3: [1212.441, null, [true,true,false]]}", SillyStructure.class);

    check(Marshaling.fromJson(Marshaling.toJson(structure), SillyStructure.class)).is(structure);
  }

/*


  @Test
  public void marshalingPolymorphicTypes() throws IOException {
    Marshaler<SillyPolyHost2> m = SillyPolyHost2Marshaler.instance();
    ImmutableList<SillyPolyHost2> list = fromJsonIterable("[{s:{b:[1,2]}},{s:{b:'b'}}]", m);
    check(fromJsonIterable(toJsonIterable(list, m), m)).is(list);
  }

  @Test
  public void marshalingPolymorphicOptionalTypes() throws IOException {
    Marshaler<SillyPolyHost2> m = SillyPolyHost2Marshaler.instance();
    ImmutableList<SillyPolyHost2> list = fromJsonIterable("[{s:{b:[1,2]},o:{b:'b'}}]", m);
    check(fromJsonIterable(toJsonIterable(list, m), m)).is(list);
    check(list.get(0).o()).isOf(ImmutableSillySub2.builder().b("b").build());
  }

  @Test(expected = IOException.class)
  public void marshalingPolymorphicTypesFailedOnUnexpectedValues() throws IOException {
    Marshaler<SillyPolyHost2> m = SillyPolyHost2Marshaler.instance();
    fromJsonIterable("[{s:{b:['a']}}}]", m);
  }

  @Test(expected = IOException.class)
  public void marshalingPolymorphicTypesFailedOnMismatchedAttributes() throws IOException {
    Marshaler<SillyPolyHost> m = SillyPolyHostMarshaler.instance();
    fromJsonIterable("[{s:{c:1}}]", m);
  }

  @Test
  public void marshalingWrapperSingleArgumentTypes() throws IOException {
    Marshaler<SillyIntWrap> m = SillyIntWrapMarshaler.instance();
    ImmutableList<SillyIntWrap> it =
        fromJsonIterable("[123,345,567]", m);

    check(fromJsonIterable(toJsonIterable(it, m), m)).is(it);
    check(fromBsonIterable(toBsonIterable(it, m), m)).is(it);
  }

  @Test
  public void unknownAttributesIgnored() throws IOException {
    Marshaler<SillyMapHolder> m1 = SillyMapHolderMarshaler.instance();

    check(fromJsonIterable("[{ unmolder2:{two:2}, sober:1}]", m1)).hasSize(1);
  }

  @Test
  public void nestedMaps() throws IOException {
    Marshaler<SillyMapTup> m = SillyMapTupMarshaler.instance();

    ImmutableList<SillyMapTup> it =
        fromJsonIterable("[[{'SOURCE':1, 'RUNTIME':2},1]]", m);

    check(fromJsonIterable(toJsonIterable(it, m), m)).is(it);
    check(fromBsonIterable(toBsonIterable(it, m), m)).is(it);

    Marshaler<SillyMapHolder> m1 = SillyMapHolderMarshaler.instance();

    ImmutableList<SillyMapHolder> it1 =
        fromJsonIterable("[{ holder1:{ONE:1,TWO:2}, holder2:{'1':'a','3':'b'}, holder3:{'ss':[{'SOURCE':1},1]}}]", m1);

    check(fromJsonIterable(toJsonIterable(it1, m1), m1)).is(it1);
    check(fromBsonIterable(toBsonIterable(it1, m1), m1)).is(it1);
  }

  @Test
  public void marshalAndUnmarshalGeneratedType() throws IOException {
    SillyStructure structure =
        fromJson("{attr1:'x', flag2:false,opt3:1, very4:33, wet5:555.55, subs6:null,"
            + " nest7:{ set2:'METHOD', set3: [1,2,4],floats4:[333.11] },"
            + "int9:0, tup3: [1212.441, null, [true,true,false]]}");

    check(fromJson(toJson(structure))).is(structure);
    check(fromBson(toBson(structure))).is(structure);
  }

  @Test
  public void importRoutinesFromPackageAnnotation() throws IOException {
    ImmutableList<SillyRoutineImport> imports =
        fromJsonIterable("['127.0.0.1:8080']", SillyRoutineImportMarshaler.instance());

    check(imports.get(0).hostAndPort().getHostText()).is("127.0.0.1");
  }

  @Test
  public void forceAndSkipEmpty() throws IOException {
    check(marshalDumb(ImmutableSillyDumb.builder().build())).is("{a:null,b:[]}");
    check(marshalDumb(ImmutableSillyDumb.builder().c3(3).addD4("X").build())).is("{a:null,b:[],c:3,d:[\"X\"]}");
  }

  private String marshalDumb(SillyDumb emptyDumb) throws IOException {
    StringWriter stringWriter = new StringWriter();
    JsonGenerator jsonGen = jsonFactory.createGenerator(stringWriter);
    SillyDumbMarshaler.marshal(jsonGen, emptyDumb);
    jsonGen.close();
    return stringWriter.toString();
  }

  private <T> ImmutableList<T> fromJsonIterable(String string, Marshaler<T> marshaler) throws IOException {
    JsonParser jsonParser = jsonFactory.createParser(string);
    jsonParser.nextToken();
    return ImmutableList.copyOf(marshaler.unmarshalIterable(jsonParser));
  }

  private <T> String toJsonIterable(Iterable<T> it, Marshaler<T> m) throws IOException {
    StringWriter writer = new StringWriter();
    JsonGenerator jsonGen = strictierJsonFactory.createGenerator(writer);
    m.marshalIterable(jsonGen, it);
    jsonGen.close();
    return writer.toString();
  }

  private <T> ImmutableList<T> fromBsonIterable(byte[] bytes, Marshaler<T> marshaler)
      throws IOException {
    JsonParser bsonParser = bsonFactory.createParser(bytes);
    bsonParser.nextToken();
    return ImmutableList.copyOf(marshaler.unmarshalIterable(bsonParser));
  }

  private <T> byte[] toBsonIterable(Iterable<T> it, Marshaler<T> marshaler) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BsonGenerator bsonGen = bsonFactory.createJsonGenerator(baos);
    marshaler.marshalIterable(bsonGen, it);
    bsonGen.close();
    return baos.toByteArray();
  }

  private SillyStructure fromJson(String string) throws IOException {
    JsonParser jsonParser = jsonFactory.createParser(string);
    jsonParser.nextToken();
    return SillyStructureMarshaler.instance().unmarshalInstance(jsonParser);
  }

  private SillyStructure fromBson(byte[] bytes) throws IOException {
    JsonParser bsonParser = bsonFactory.createParser(bytes);
    bsonParser.nextToken();
    return SillyStructureMarshaler.instance().unmarshalInstance(bsonParser);
  }

  private String toJson(SillyStructure structure) throws IOException {
    StringWriter stringWriter = new StringWriter();
    JsonGenerator jsonGen = jsonFactory.createGenerator(stringWriter);
    SillyStructureMarshaler.marshal(jsonGen, structure);
    jsonGen.close();
    return stringWriter.toString();
  }

  private byte[] toBson(SillyStructure structure) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BsonGenerator bsonGen = bsonFactory.createJsonGenerator(baos);
    SillyStructureMarshaler.marshal(bsonGen, structure);
    bsonGen.close();
    return baos.toByteArray();
  }*/

  private <T> List<T> fromJsonIterable(String json, TypeToken<List<T>> typeToken) {
    return Marshaling.getGson().fromJson(json, typeToken.getType());
  }

  private <T> String toJsonIterable(List<? extends T> list, TypeToken<List<T>> typeToken) {
    return Marshaling.getGson().toJson(list, typeToken.getType());
  }
}
