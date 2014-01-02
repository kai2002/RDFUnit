package org.aksw.databugger.Utils;

import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.shared.uuid.JenaUUID;
import org.aksw.databugger.enums.TestAppliesTo;
import org.aksw.databugger.enums.TestGenerationType;
import org.aksw.databugger.exceptions.TripleWriterException;
import org.aksw.databugger.io.TripleWriter;
import org.aksw.databugger.services.PrefixService;
import org.aksw.databugger.sources.Source;
import org.aksw.databugger.tests.TestAnnotation;
import org.aksw.databugger.tests.TestAutoGenerator;
import org.aksw.databugger.tests.TestCase;
import org.aksw.jena_sparql_api.core.QueryExecutionFactory;
import org.aksw.jena_sparql_api.model.QueryExecutionFactoryModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * User: Dimitris Kontokostas
 * Various utility test functions for tests
 * Created: 9/24/13 10:59 AM
 */
public class TestUtils {
    private static final Logger log = LoggerFactory.getLogger(TestUtils.class);

    public static List<TestAutoGenerator> instantiateTestGeneratorsFromModel(QueryExecutionFactory queryFactory) {
        List<TestAutoGenerator> autoGenerators = new ArrayList<TestAutoGenerator>();

        String sparqlSelect = DatabuggerUtils.getAllPrefixes() +
                " SELECT ?generator ?desc ?query ?patternID WHERE { " +
                " ?generator a tddo:TestGenerator ; " +
                "  dcterms:description ?desc ; " +
                "  tddo:sparqlGenerator ?query ; " +
                "  tddo:basedOnPattern ?pattern . " +
                " ?pattern dcterms:identifier ?patternID ." +
                "} ";

        QueryExecution qe = queryFactory.createQueryExecution(sparqlSelect);
        ResultSet results = qe.execSelect();

        while (results.hasNext()) {
            QuerySolution qs = results.next();

            String generator = qs.get("generator").toString();
            String description = qs.get("desc").toString();
            String query = qs.get("query").toString();
            String patternID = qs.get("patternID").toString();

            TestAutoGenerator tag = new TestAutoGenerator(generator, description, query, patternID);
            if (tag.isValid())
                autoGenerators.add(tag);
            else {
                log.error("AutoGenerator not valid: " + tag.getURI());
                System.exit(-1);
            }
        }
        qe.close();

        return autoGenerators;

    }

    public static List<TestCase> instantiateTestsFromAG(List<TestAutoGenerator> autoGenerators, Source source) {
        List<TestCase> tests = new ArrayList<TestCase>();

        for (TestAutoGenerator tag : autoGenerators) {
            tests.addAll(tag.generate(source));
        }

        return tests;

    }


    public static List<TestCase> instantiateTestsFromModel(Model model) {
        List<TestCase> tests = new ArrayList<TestCase>();
        QueryExecutionFactory qef = new QueryExecutionFactoryModel(model);

        String sparqlSelect = DatabuggerUtils.getAllPrefixes() +
                " SELECT DISTINCT ?testURI ?appliesTo ?basedOnPattern ?generated ?source ?sparqlWhere ?sparqlPrevalence ?references ?testGenerator WHERE { " +
                " ?testURI a tddo:TestCase ; " +
                " tddo:appliesTo ?appliesTo ;" +
                " tddo:basedOnPattern ?basedOnPattern ;" +
                " tddo:generated ?generated ;" +
                " tddo:source ?source ;" +
                " tddo:sparqlWhere ?sparqlWhere ;" +
                " tddo:sparqlPrevalence ?sparqlPrevalence ;" +
                " OPTIONAL {?testURI tddo:references ?references .}" +
                " OPTIONAL {?testURI tddo:testGenerator ?testGenerator .}" +
                "} ORDER BY ?testURI ";

        QueryExecution qe = qef.createQueryExecution(sparqlSelect);
        ResultSet results = qe.execSelect();


        TestCase lastTest = new TestCase("", "", "");
        while (results.hasNext()) {
            QuerySolution qs = results.next();

            String testURI = qs.get("testURI").toString();

            String appliesTo = qs.get("appliesTo").toString();
            String basedOnPattern = qs.get("basedOnPattern").toString();
            String generated = qs.get("generated").toString();
            String source = qs.get("source").toString();
            String sparqlWhere = qs.get("sparqlWhere").toString();
            String sparqlPrevalence = qs.get("sparqlPrevalence").toString();
            //optional / check if exists
            List<String> referencesLst = new ArrayList<String>();
            String references = "";
            if (qs.contains("references")) {
                references = qs.get("references").toString();
                if (!references.equals("")) {
                    referencesLst.add(references);
                }
            }
            String testGenerator = "";
            if (qs.contains("testGenerator"))
                testGenerator = qs.get("testGenerator").toString();


            TestCase currentTest = new TestCase(
                    testURI,
                    basedOnPattern.replace(PrefixService.getPrefix("tddp"), ""),
                    TestGenerationType.resolve(generated),
                    testGenerator,
                    TestAppliesTo.resolve(appliesTo),
                    source,
                    new TestAnnotation(),
                    sparqlWhere,
                    sparqlPrevalence,
                    referencesLst);

            if (lastTest.getPattern() == null) {
                lastTest = currentTest.clone();
                continue;
            }
            if (lastTest.getTestURI() != testURI) {
                tests.add(lastTest);
                lastTest = currentTest.clone();
            } else {
                lastTest.addReferences(currentTest.getReferences());
            }
        }
        // add last row
        if (!(lastTest.getPattern() == null))
            tests.add(lastTest);
        qe.close();


        return tests;

    }

    public static void writeTestsToFile(List<TestCase> tests, TripleWriter testCache) {
        Model model = ModelFactory.createDefaultModel();
        for (TestCase t : tests)
            t.saveTestToModel(model);
        try {
            model.setNsPrefixes(PrefixService.getPrefixMap());
            testCache.write(model);
        } catch (TripleWriterException e) {
            log.error("Cannot cache tests: " + e.getMessage());
        }
    }

    public static String generateTestURI(String sourcePrefix, String patternID, String string2hash) {
        String testURI = PrefixService.getPrefix("tddt") + sourcePrefix + "-" + patternID + "-";
        String md5Hash = TestUtils.MD5(string2hash);
        if (md5Hash == null)
            testURI += JenaUUID.generate().asString();
        else
            testURI += md5Hash;
        return testURI;
    }

    // Taken from http://stackoverflow.com/questions/415953/generate-md5-hash-in-java
    public static String MD5(String md5) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] array = md.digest(md5.getBytes());
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < array.length; ++i) {
                sb.append(Integer.toHexString((array[i] & 0xFF) | 0x100).substring(1, 3));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
        }
        return null;
    }
}
