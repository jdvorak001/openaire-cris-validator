package org.eurocris.openaire.cris.validator;

import static javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.bind.JAXBElement;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.apache.commons.cli.MissingArgumentException;
import org.eurocris.openaire.cris.validator.OAIPMHEndpoint.ConnectionStreamFactory;
import org.eurocris.openaire.cris.validator.OAIPMHEndpoint.ValidationMode;
import org.eurocris.openaire.cris.validator.tree.CERIFNode;
import org.eurocris.openaire.cris.validator.util.CheckingIterable;
import org.eurocris.openaire.cris.validator.util.FileSavingInputStream;
import org.eurocris.openaire.cris.validator.util.XmlUtils;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runners.MethodSorters;
import org.openarchives.oai._2.DescriptionType;
import org.openarchives.oai._2.HeaderType;
import org.openarchives.oai._2.IdentifyType;
import org.openarchives.oai._2.MetadataFormatType;
import org.openarchives.oai._2.MetadataType;
import org.openarchives.oai._2.RecordType;
import org.openarchives.oai._2.SetType;
import org.openarchives.oai._2.StatusType;
import org.openarchives.oai._2_0.oai_identifier.OaiIdentifierType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

@FixMethodOrder( value=MethodSorters.NAME_ASCENDING )
public class CRISValidator {
	
	public static final String OPENAIRE_CRIS_EQUIPMENTS__SET_SPEC = "openaire_cris_equipments";
	public static final String OPENAIRE_CRIS_EVENTS__SET_SPEC = "openaire_cris_events";
	public static final String OPENAIRE_CRIS_FUNDING__SET_SPEC = "openaire_cris_funding";
	public static final String OPENAIRE_CRIS_PROJECTS__SET_SPEC = "openaire_cris_projects";
	public static final String OPENAIRE_CRIS_ORGUNITS__SET_SPEC = "openaire_cris_orgunits";
	public static final String OPENAIRE_CRIS_PERSONS__SET_SPEC = "openaire_cris_persons";
	public static final String OPENAIRE_CRIS_PATENTS__SET_SPEC = "openaire_cris_patents";
	public static final String OPENAIRE_CRIS_PRODUCTS__SET_SPEC = "openaire_cris_products";
	public static final String OPENAIRE_CRIS_PUBLICATIONS__SET_SPEC = "openaire_cris_publications";
	
	public static final String OAI_CERIF_OPENAIRE__METADATA_PREFIX = "oai_cerif_openaire";
	
	public static final String OPENAIRE_CERIF_XMLNS = "https://www.openaire.eu/cerif-profile/1.1/";
	
	public static final ValidationMode VALIDATION_MODE = ValidationMode.REPOSITORY_META_REQUESTS_ONLY;
	
	public static final ConnectionStreamFactory CONN_STREAM_FACTORY = new FileLoggingConnectionStreamFactory( "data" );
	
	public static void main( final String[] args ) throws Exception {
		final String endpointUrl = ( args.length > 0 ) ? args[0] : null;
		final URL endpointBaseUrl = new URL( endpointUrl );
		endpoint = new OAIPMHEndpoint( endpointBaseUrl, VALIDATION_MODE, createParserSchema(), CONN_STREAM_FACTORY );
		JUnitCore.main( CRISValidator.class.getName() );
	}
	
	private static OAIPMHEndpoint endpoint;

	public CRISValidator() throws MalformedURLException, MissingArgumentException, SAXException {
		if ( endpoint == null ) {
			final String endpointPropertyKey = "endpoint.to.validate";
			final String endpointUrl = System.getProperty( endpointPropertyKey );
			if ( endpointUrl == null ) {
				throw new MissingArgumentException( "Please specify the OAI-PMH endpoint URL as the value of the " + endpointPropertyKey + " system property or as the first argument on the command line" );
			}
			final URL endpointBaseUrl = new URL( endpointUrl );
			endpoint = new OAIPMHEndpoint( endpointBaseUrl, VALIDATION_MODE, createParserSchema(), CONN_STREAM_FACTORY );			
		}
	}
	
	public String getName() {
		return endpoint.getBaseUrl();
	}
	
	private static Schema schema = null;
	
	protected static synchronized Schema createParserSchema() throws SAXException {
		if ( schema == null ) {
			final SchemaFactory sf = SchemaFactory.newInstance( W3C_XML_SCHEMA_NS_URI );
			final Source[] schemas = { 
					schema( "/openaire-cerif-profile.xsd" ), 
					schema( "/cached/oai-identifier.xsd" ), 
					schema( "/cached/OAI-PMH.xsd" ), 
					schema( "/cached/oai_dc.xsd" ), 
					schema( "/cached/xml.xsd", "http://www.w3.org/2001/xml.xsd" ), 
				};
			schema = sf.newSchema( schemas );
		}
		return schema;
	}

	private static Source schema( final String path ) {
		return schema( path, null );
	}
	
	private static Source schema( final String path, final String externalUrl ) {
		final String path1 = "/schemas" + path;
		final URL url = OAIPMHEndpoint.class.getResource( path1 );
		if ( url == null ) {
			throw new IllegalArgumentException( "Resource " + path1 + " not found" );
		}
		final StreamSource src = new StreamSource();
		src.setInputStream( OAIPMHEndpoint.class.getResourceAsStream( path1 ) );
		src.setSystemId( ( externalUrl != null ) ? externalUrl : url.toExternalForm() );
		return src;
	}

	@SuppressWarnings( "unused")
	private static Optional<String> sampleIdentifier = Optional.empty();

	private static Optional<String> serviceAcronym = Optional.empty();
	
	@Test
	public void check000_Identify() throws Exception {
		final IdentifyType identify = endpoint.callIdentify();
		CheckingIterable<DescriptionType> checker = CheckingIterable.over( identify.getDescription() );
		checker = checker.checkContainsOne( new Predicate<DescriptionType>() {

			@Override
			public boolean test( final DescriptionType description ) {
				final Object obj = description.getAny();
				if ( obj instanceof JAXBElement<?> ) {
					final JAXBElement<?> jaxbEl = (JAXBElement<?>) obj;
					final Object obj1 = jaxbEl.getValue();
					if ( obj1 instanceof OaiIdentifierType ) {
						final OaiIdentifierType oaiIdentifier = (OaiIdentifierType) obj1;
						sampleIdentifier = Optional.ofNullable( oaiIdentifier.getSampleIdentifier() );
						return true;
					}
				}
				return false;
			}
			
		}, "the Identify descriptions list (1b)", "an 'oai-identifier' element" );
		checker = checker.checkContainsOne( new Predicate<DescriptionType>() {

			@Override
			public boolean test( final DescriptionType description ) {
				final Object obj = description.getAny();
				if ( obj instanceof Element ) {
					final Element el = (Element) obj;
					if ( "Service".equals( el.getLocalName() ) && OPENAIRE_CERIF_XMLNS.equals( el.getNamespaceURI() ) ) {
						serviceAcronym = XmlUtils.getTextContents( XmlUtils.getFirstMatchingChild( el, "Acronym", el.getNamespaceURI() ) );
						return true;
					}
				}
				return false;
			}
			
		}, "the Identify descriptions list (1a)", "a 'Service' element" );
		checker.run();
		if ( ! endpoint.getBaseUrl().startsWith( "file:" ) ) {
			assertEquals( "Identify response has a different endpoint base URL (1d)", endpoint.getBaseUrl(), identify.getBaseURL() );
		}
		final Optional<String> repoIdentifier = endpoint.getRepositoryIdentifer();
		if ( serviceAcronym.isPresent() && repoIdentifier.isPresent() ) {
			assertEquals( "Service acronym is not the same as the repository identifier (1c)", serviceAcronym.get(), repoIdentifier.get() );
		}
	}
	
	@Test
	public void check010_MetadataFormats() throws Exception {
		CheckingIterable<MetadataFormatType> checker = CheckingIterable.over( endpoint.callListMetadataFormats().getMetadataFormat() );
		checker = checker.checkUnique( MetadataFormatType::getMetadataPrefix, "Metadata prefix not unique" );
		checker = checker.checkUnique( MetadataFormatType::getMetadataNamespace, "Metadata namespace not unique" );
		checker = checker.checkUnique( MetadataFormatType::getSchema, "Metadata schema location not unique" );
		checker = wrapCheckMetadataFormatPresent( checker, OAI_CERIF_OPENAIRE__METADATA_PREFIX, OPENAIRE_CERIF_XMLNS );
		checker.run();
	}
	
	private CheckingIterable<MetadataFormatType> wrapCheckMetadataFormatPresent( final CheckingIterable<MetadataFormatType> parent, final String expectedMetadataFormatPrefix, final String expectedMetadataFormatNamespace ) {
		final Predicate<MetadataFormatType> predicate = new Predicate<MetadataFormatType>() {

			@Override
			public boolean test( final MetadataFormatType mf ) {
				if ( expectedMetadataFormatPrefix.equals( mf.getMetadataPrefix() ) ) {
					assertEquals( "Non-matching set name for set '" + expectedMetadataFormatPrefix + "' (2)", expectedMetadataFormatNamespace, mf.getMetadataNamespace() );
					final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
					dbf.setNamespaceAware( true );
					dbf.setValidating( false );
					dbf.setIgnoringComments( true );
					DocumentBuilder db;
					try {
						db = dbf.newDocumentBuilder();
						final String schemaUrl = mf.getSchema();
						System.out.println( "Fetching " + schemaUrl );
						final Document doc = db.parse( schemaUrl );
						final Element schemaRootEl = doc.getDocumentElement();
						final String targetNsUri = schemaRootEl.getAttribute( "targetNamespace" );
						assertEquals( "The schema does not have the advertised target namespace URI (2)", mf.getMetadataNamespace(), targetNsUri );
					} catch ( final ParserConfigurationException | SAXException | IOException e ) {
						throw new IllegalStateException( e ); 
					}
					return true;
				}
				return false;
			}

		};
		return parent.checkContains( predicate, new AssertionError( "MetadataFormat '" + expectedMetadataFormatPrefix + "' not present (2)" ) );
	}
	
	@Test
	public void check020_Sets() throws Exception {
		CheckingIterable<SetType> checker = CheckingIterable.over( endpoint.callListSets() );
		checker = checker.checkUnique( SetType::getSetSpec, "setSpec not unique" );
		checker = wrapCheckSetPresent( checker, OPENAIRE_CRIS_PUBLICATIONS__SET_SPEC, "OpenAIRE_CRIS_publications" );
		checker = wrapCheckSetPresent( checker, OPENAIRE_CRIS_PRODUCTS__SET_SPEC, "OpenAIRE_CRIS_products" );
		checker = wrapCheckSetPresent( checker, OPENAIRE_CRIS_PATENTS__SET_SPEC, "OpenAIRE_CRIS_patents" );
		checker = wrapCheckSetPresent( checker, OPENAIRE_CRIS_PERSONS__SET_SPEC, "OpenAIRE_CRIS_persons" );
		checker = wrapCheckSetPresent( checker, OPENAIRE_CRIS_ORGUNITS__SET_SPEC, "OpenAIRE_CRIS_orgunits" );
		checker = wrapCheckSetPresent( checker, OPENAIRE_CRIS_PROJECTS__SET_SPEC, "OpenAIRE_CRIS_projects" );
		checker = wrapCheckSetPresent( checker, OPENAIRE_CRIS_FUNDING__SET_SPEC, "OpenAIRE_CRIS_funding" );
		checker = wrapCheckSetPresent( checker, OPENAIRE_CRIS_EVENTS__SET_SPEC, "OpenAIRE_CRIS_events" );
		checker = wrapCheckSetPresent( checker, OPENAIRE_CRIS_EQUIPMENTS__SET_SPEC, "OpenAIRE_CRIS_equipments" );
		checker.run();
	}
	
	private CheckingIterable<SetType> wrapCheckSetPresent( final CheckingIterable<SetType> parent, final String expectedSetSpec, final String expectedSetName ) {
		final Predicate<SetType> predicate = new Predicate<SetType>() {

			@Override
			public boolean test( final SetType s ) {
				if ( expectedSetSpec.equals( s.getSetSpec() ) ) {
					assertEquals( "Non-matching set name for set '" + expectedSetSpec + "' (3)", expectedSetName, s.getSetName() );
					return true;
				}
				return false;
			}

		};
		return parent.checkContains( predicate, new AssertionError( "Set '" + expectedSetSpec + "' not present (3)" ) );
	}

	@Test
	public void check100_CheckPublications() throws Exception {
		final Iterable<RecordType> records = endpoint.callListRecords( OAI_CERIF_OPENAIRE__METADATA_PREFIX, OPENAIRE_CRIS_PUBLICATIONS__SET_SPEC, null, null );
		final CheckingIterable<RecordType> checker = buildCommonCheckersChain( records );
		checker.run();
	}

	@Test
	public void check200_CheckProducts() throws Exception {
		final Iterable<RecordType> records = endpoint.callListRecords( OAI_CERIF_OPENAIRE__METADATA_PREFIX, OPENAIRE_CRIS_PRODUCTS__SET_SPEC, null, null );
		final CheckingIterable<RecordType> checker = buildCommonCheckersChain( records );
		checker.run();
	}

	@Test
	public void check300_CheckPatents() throws Exception {
		final Iterable<RecordType> records = endpoint.callListRecords( OAI_CERIF_OPENAIRE__METADATA_PREFIX, OPENAIRE_CRIS_PATENTS__SET_SPEC, null, null );
		final CheckingIterable<RecordType> checker = buildCommonCheckersChain( records );
		checker.run();
	}

	@Test
	public void check400_CheckPersons() throws Exception {
		final Iterable<RecordType> records = endpoint.callListRecords( OAI_CERIF_OPENAIRE__METADATA_PREFIX, OPENAIRE_CRIS_PERSONS__SET_SPEC, null, null );
		final CheckingIterable<RecordType> checker = buildCommonCheckersChain( records );
		checker.run();
	}

	@Test
	public void check500_CheckOrgUnits() throws Exception {
		final Iterable<RecordType> records = endpoint.callListRecords( OAI_CERIF_OPENAIRE__METADATA_PREFIX, OPENAIRE_CRIS_ORGUNITS__SET_SPEC, null, null );
		final CheckingIterable<RecordType> checker = buildCommonCheckersChain( records );
		checker.run();
	}

	@Test
	public void check600_CheckProjects() throws Exception {
		final Iterable<RecordType> records = endpoint.callListRecords( OAI_CERIF_OPENAIRE__METADATA_PREFIX, OPENAIRE_CRIS_PROJECTS__SET_SPEC, null, null );
		final CheckingIterable<RecordType> checker = buildCommonCheckersChain( records );
		checker.run();
	}

	@Test
	public void check700_CheckFundings() throws Exception {
		final Iterable<RecordType> records = endpoint.callListRecords( OAI_CERIF_OPENAIRE__METADATA_PREFIX, OPENAIRE_CRIS_FUNDING__SET_SPEC, null, null );
		final CheckingIterable<RecordType> checker = buildCommonCheckersChain( records );
		checker.run();
	}

	@Test
	public void check800_CheckEquipment() throws Exception {
		final Iterable<RecordType> records = endpoint.callListRecords( OAI_CERIF_OPENAIRE__METADATA_PREFIX, OPENAIRE_CRIS_EQUIPMENTS__SET_SPEC, null, null );
		final CheckingIterable<RecordType> checker = buildCommonCheckersChain( records );
		checker.run();
	}

	@Test
	public void check900_CheckEvents() throws Exception {
		final Iterable<RecordType> records = endpoint.callListRecords( OAI_CERIF_OPENAIRE__METADATA_PREFIX, OPENAIRE_CRIS_EVENTS__SET_SPEC, null, null );
		final CheckingIterable<RecordType> checker = buildCommonCheckersChain( records );
		checker.run();
	}

	/**
	 * Prepare the checks to run on all CERIF records. 
	 * @param records
	 * @return
	 */
	protected CheckingIterable<RecordType> buildCommonCheckersChain( final Iterable<RecordType> records ) {
		return wrapCheckPayloadNamespaceAndAccummulate( wrapCheckUniqueness( wrapCheckOAIIdentifier( CheckingIterable.over( records ) ) ) );
	}
	
	private CheckingIterable<RecordType> wrapCheckUniqueness( final CheckingIterable<RecordType> checker ) {
		final Function<RecordType, HeaderType> f1 = RecordType::getHeader;
		return checker.checkUnique( f1.andThen( HeaderType::getIdentifier ), "record identifier not unique" );
	}
	
	private CheckingIterable<RecordType> wrapCheckOAIIdentifier( final CheckingIterable<RecordType> checker ) {
		final Optional<String> repoIdentifier = endpoint.getRepositoryIdentifer();
		if ( repoIdentifier.isPresent() ) {
			final Function<RecordType, String> expectedFunction = new Function<RecordType, String>() {
				
				@Override
				public String apply( final RecordType x ) {
					final MetadataType metadata = x.getMetadata();
					if ( metadata != null ) {
						final Element el = (Element) metadata.getAny();
						return "oai:" + repoIdentifier.get() + ":" + el.getLocalName() + "s/" + el.getAttribute( "id" );
					} else {
						// make the test trivially satisfied for records with no metadata
						return x.getHeader().getIdentifier();
					}
				}

			};
			return checker.checkForAllEquals( expectedFunction, ( final RecordType record ) -> ( record.getHeader().getIdentifier() ), "OAI identifier other than expected" );
		} else {
			return checker;
		}
	}

	private static Map<String, CERIFNode> recordsByName = new HashMap<>();
	private static Map<String, CERIFNode> recordsByOaiIdentifier = new HashMap<>();
	
	private CheckingIterable<RecordType> wrapCheckPayloadNamespaceAndAccummulate( final CheckingIterable<RecordType> checker ) {
		return checker.checkForAll( new Predicate<RecordType>() {

			@Override
			public boolean test( final RecordType t ) {
				final MetadataType recordMetadata = t.getMetadata();
				if ( recordMetadata != null ) {
					final Object obj = recordMetadata.getAny();
					if ( obj instanceof Element ) {
						final Element el = (Element) obj;
						assertEquals( "The payload element not in the right namespace", OPENAIRE_CERIF_XMLNS, el.getNamespaceURI() );
						final CERIFNode node = CERIFNode.buildTree( el );
						recordsByName.put( node.getName(), node );
						recordsByOaiIdentifier.put( t.getHeader().getIdentifier(), node );
						return true;
					}
				}
				// fail unless the record is deleted
				return StatusType.DELETED.equals( t.getHeader().getStatus() );
			}
			
		}, "Metadata missing from OAI-PMH record" );
	}
	
	private static final String[] types = new String[] { "Publication", "Product", "Patent", "Person", "OrgUnit", "Project", "Funding", "Event", "Equipment" };
	static {
		Arrays.sort( types );
	}
	
	@Test
	public void check990_CheckReferentialIntegrityAndFunctionalDependency() {
		for ( final Map.Entry<String, CERIFNode> entry : recordsByOaiIdentifier.entrySet() ) {
			final String oaiIdentifier = entry.getKey();
			final CERIFNode node = entry.getValue();
			// for all harvested CERIF data, check the children of the main objects (no need to check the objects themselves, they satisfy all checks trivially)
			for ( final CERIFNode node3 : node.getChildren( null ) ) {
				lookForCERIFObjectsAndCheckReferentialIntegrityAndFunctionalDependency( node3, oaiIdentifier );
			}
		}
	}

	private void lookForCERIFObjectsAndCheckReferentialIntegrityAndFunctionalDependency( final CERIFNode node, final String oaiIdentifier ) {
		// do the checks if this is a CERIF object
		final String type = node.getType();
		if ( Arrays.binarySearch( types, type ) >= 0 ) {
			doCheckFunctionalDependency( node, oaiIdentifier );
		}
		// recurse to children of this node
		for ( final CERIFNode node2 : node.getChildren( null ) ) {
			lookForCERIFObjectsAndCheckReferentialIntegrityAndFunctionalDependency( node2, oaiIdentifier );
		}
	}

	private void doCheckFunctionalDependency( final CERIFNode node, final String oaiIdentifier ) {
		final String name = node.getName();
		if ( name.contains( "[@id=\"" ) ) {
			final CERIFNode baseNode = recordsByName.get( name );
			assertNotNull( "Record for " + name + " not found, referential integrity violated in " + oaiIdentifier + " (5a)", baseNode );
			if ( ! node.isSubsetOf( baseNode ) ) {
				final CERIFNode missingNode = node.reportWhatIMiss( baseNode ).get();
				fail( "Violation of (5b) in " + oaiIdentifier + ":\n" + node + "is not subset of\n" + baseNode + "missing is\n" + missingNode );
			}
		}
	}
	
}

/**
 * A {@link ConnectionStreamFactory} that logs the input as files in a given directory.
 * @author jdvorak
 */
class FileLoggingConnectionStreamFactory implements OAIPMHEndpoint.ConnectionStreamFactory {

	private final String logDir;
	
	/**
	 * The factory with the given directory to place the files in.
	 * @param logDir the directory for the files
	 */
	public FileLoggingConnectionStreamFactory( final String logDir ) {
		this.logDir = logDir;
	}
	
	private static final Pattern p2 = Pattern.compile( ".*\\W(set=\\w+).*" );
	private static final Pattern p1 = Pattern.compile( ".*\\W(verb=\\w+).*" );

	@Override
	public InputStream makeInputStream( final URLConnection conn ) throws IOException {
		InputStream inputStream = conn.getInputStream();
		if ( logDir != null ) {
			final Path logDirPath = Paths.get( logDir );
			Files.createDirectories( logDirPath );
			final StringBuilder sb = new StringBuilder();
			final String url2 = conn.getURL().toExternalForm();
			final Matcher m1 = p1.matcher( url2 );
			if ( m1.matches() ) {
				sb.append( m1.group( 1 ) );
			}
			final Matcher m2 = p2.matcher( url2 );
 			if ( m2.matches() ) {
 				sb.append( "__" );
 				sb.append( m2.group( 1 ) );
 			}
			final DateTimeFormatter dtf = DateTimeFormatter.ofPattern( "yyyyMMdd'T'HHmmss.SSS" );
			final String logFilename = "oai-pmh--" + dtf.format( LocalDateTime.now() ) + "--" + sb.toString() + ".xml";
			inputStream = new FileSavingInputStream( inputStream, logDirPath.resolve( logFilename ) );
		}
		return inputStream;
	}
	
}