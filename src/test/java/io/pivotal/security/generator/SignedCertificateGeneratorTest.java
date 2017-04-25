package io.pivotal.security.generator;

import io.pivotal.security.config.BouncyCastleProviderConfiguration;
import io.pivotal.security.domain.CertificateParameters;
import io.pivotal.security.request.CertificateGenerationParameters;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNamesBuilder;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.cert.X509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;

import static io.pivotal.security.helper.SpectrumHelper.getBouncyCastleProvider;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = BouncyCastleProviderConfiguration.class)
public class SignedCertificateGeneratorTest {

  private SignedCertificateGenerator subject;
  private X500Name issuerDn;
  private KeyPair issuerKey;
  private KeyPair generatedCertificateKeyPair;
  private CertificateParameters certificateParameters;
  private KeyPairGenerator generator;
  private X509ExtensionUtils x509ExtensionUtils;
  private BouncyCastleProvider bcProvider;
  private RandomSerialNumberGenerator serialNumberGenerator;
  private DateTimeProvider timeProvider;
  private Calendar now;
  private Calendar later;
  private final int expectedDurationInDays = 10;
  private final String expectedCertificateCommonName = "my cert name";
  private final byte[] expectedSubjectKeyIdentifier = "expected subject key identifier".getBytes();
  private final String[] alternateNames = {"alt1", "alt2"};
  private final String[] keyUsage = {"digital_signature", "key_encipherment"};
  private final String[] extendedKeyUsage = {"server_auth", "code_signing"};

  @Autowired
  private JcaContentSignerBuilder jcaContentSignerBuilder;

  @Autowired
  private JcaX509CertificateConverter jcaX509CertificateConverter;

  @Before
  public void beforeEach() throws Exception {
    timeProvider = mock(DateTimeProvider.class);
    now = Calendar.getInstance();
    now.setTimeInMillis(1493066824);
    later = (Calendar) now.clone();
    later.add(Calendar.DAY_OF_YEAR, expectedDurationInDays);
    when(timeProvider.getNow()).thenReturn(now);
    serialNumberGenerator = mock(RandomSerialNumberGenerator.class);
    when(serialNumberGenerator.generate()).thenReturn(BigInteger.valueOf(1337));
    bcProvider = getBouncyCastleProvider();
    x509ExtensionUtils = mock(X509ExtensionUtils.class);
    when(x509ExtensionUtils.createSubjectKeyIdentifier(any())).thenReturn(new SubjectKeyIdentifier(expectedSubjectKeyIdentifier));

    generator = KeyPairGenerator
        .getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
    generator.initialize(1024); // doesn't matter for testing

    issuerDn = new X500Name("CN=ca DN,O=credhub");
    issuerKey = generator.generateKeyPair();
    generatedCertificateKeyPair = generator.generateKeyPair();
    certificateParameters = defaultCertificateParameters();

    subject = new SignedCertificateGenerator(timeProvider,
        serialNumberGenerator,
        x509ExtensionUtils,
        jcaContentSignerBuilder,
        jcaX509CertificateConverter);
  }

  @Test
  public void getSelfSigned_generatesACertificateWithTheRightValues() throws Exception {
    X509Certificate generatedCertificate = subject.getSelfSigned(generatedCertificateKeyPair, certificateParameters);

    assertThat(generatedCertificate.getIssuerDN().getName(), containsString("CN=my cert name"));
    assertThat(generatedCertificate.getSubjectDN().toString(), containsString("CN=my cert name"));
    generatedCertificate.verify(generatedCertificateKeyPair.getPublic());
  }

  @Test
  public void getSignedByIssuer_generatesACertificateWithTheRightValues() throws Exception {
    X509Certificate generatedCertificate = subject.getSignedByIssuer(issuerDn, issuerKey.getPrivate(), generatedCertificateKeyPair, certificateParameters);

    assertThat(generatedCertificate.getIssuerDN().getName(), containsString("CN=ca DN"));
    assertThat(generatedCertificate.getIssuerDN().getName(), containsString("O=credhub"));

    assertThat(generatedCertificate.getSerialNumber(), equalTo(BigInteger.valueOf(1337l)));
    assertThat(generatedCertificate.getNotBefore().toString(), equalTo(Date.from(now.toInstant()).toString()));
    assertThat(generatedCertificate.getNotAfter().toString(), equalTo(Date.from(later.toInstant()).toString()));
    assertThat(generatedCertificate.getSubjectDN().toString(), containsString("CN=my cert name"));
    assertThat(generatedCertificate.getPublicKey(), equalTo(generatedCertificateKeyPair.getPublic()));
    assertThat(generatedCertificate.getSigAlgName(), equalTo("SHA256WITHRSA"));
    generatedCertificate.verify(issuerKey.getPublic());

    byte[] isCaExtension = generatedCertificate.getExtensionValue(Extension.basicConstraints.getId());
    assertThat(Arrays.copyOfRange(isCaExtension, 2, isCaExtension.length),
        equalTo(new BasicConstraints(true).getEncoded()));
  }


  @Test
  public void getSignedByIssuer_setsSubjectKeyIdentifier() throws Exception {
    X509Certificate generatedCertificate = subject.getSignedByIssuer(issuerDn, issuerKey.getPrivate(), generatedCertificateKeyPair, certificateParameters);

    byte[] actual = generatedCertificate.getExtensionValue(Extension.subjectKeyIdentifier.getId());
    // four bit type field is added at the beginning as per RFC 5280
    assertThat(Arrays.copyOfRange(actual, 4, actual.length), equalTo(expectedSubjectKeyIdentifier));
  }

  @Test
  public void getSignedByIssuer_setsAlternativeName_ifPresent() throws Exception {
    X509Certificate generatedCertificate = subject.getSignedByIssuer(issuerDn, issuerKey.getPrivate(), generatedCertificateKeyPair, certificateParameters);

    assertThat(generatedCertificate.getExtensionValue(Extension.subjectAlternativeName.getId()), nullValue());

    certificateParameters = parametersContainsExtensions();
    generatedCertificate = subject.getSignedByIssuer(issuerDn, issuerKey.getPrivate(), generatedCertificateKeyPair, certificateParameters);

    byte[] actualSubjectAlternativeName = generatedCertificate.getExtensionValue(Extension.subjectAlternativeName.getId());
    byte[] expectedAlternativeName = getExpectedAlternativeNames();
    assertThat(Arrays.copyOfRange(actualSubjectAlternativeName, 2, actualSubjectAlternativeName.length),
        equalTo(expectedAlternativeName));
  }

  @Test
  public void getSignedByIssuer_setsKeyUsage_ifPresent() throws Exception {
    X509Certificate generatedCertificate = subject.getSignedByIssuer(issuerDn, issuerKey.getPrivate(), generatedCertificateKeyPair, certificateParameters);

    assertThat(generatedCertificate.getExtensionValue(Extension.keyUsage.getId()), nullValue());

    certificateParameters = parametersContainsExtensions();

    generatedCertificate = subject.getSignedByIssuer(issuerDn, issuerKey.getPrivate(), generatedCertificateKeyPair, certificateParameters);
    byte[] actualKeyUsage = generatedCertificate.getExtensionValue(Extension.keyUsage.getId());

      assertThat(Arrays.copyOfRange(actualKeyUsage, 5, actualKeyUsage.length),
          equalTo(certificateParameters.getKeyUsage().getBytes()));
  }

  @Test
  public void getSignedByIssuer_setsExtendedKeyUsage_ifPresent() throws Exception {
    X509Certificate generatedCertificate = subject.getSignedByIssuer(issuerDn, issuerKey.getPrivate(), generatedCertificateKeyPair, certificateParameters);

    assertThat(generatedCertificate.getExtensionValue(Extension.keyUsage.getId()), nullValue());

    certificateParameters = parametersContainsExtensions();

    generatedCertificate = subject.getSignedByIssuer(issuerDn, issuerKey.getPrivate(), generatedCertificateKeyPair, certificateParameters);
    byte[] actualKeyUsage = generatedCertificate.getExtensionValue(Extension.extendedKeyUsage.getId());

    assertThat(Arrays.copyOfRange(actualKeyUsage, 2, actualKeyUsage.length),
        equalTo(certificateParameters.getExtendedKeyUsage().getEncoded()));
  }

  private byte[] getExpectedAlternativeNames() throws IOException {
    return new GeneralNamesBuilder()
        .addName(new GeneralName(GeneralName.dNSName, alternateNames[0]))
        .addName(new GeneralName(GeneralName.dNSName, alternateNames[1])).build().getEncoded();
  }

  private CertificateParameters defaultCertificateParameters() {
    return new CertificateParameters(
        new CertificateGenerationParameters()
            .setDuration(expectedDurationInDays)
            .setCommonName(expectedCertificateCommonName)
            .setIsCa(true)
    );
  }

  private CertificateParameters parametersContainsExtensions() {
    return new CertificateParameters(
        new CertificateGenerationParameters()
            .setDuration(expectedDurationInDays)
            .setCommonName(expectedCertificateCommonName)
            .setAlternativeNames(alternateNames)
            .setKeyUsage(keyUsage)
            .setExtendedKeyUsage(extendedKeyUsage)
    );
  }
}
