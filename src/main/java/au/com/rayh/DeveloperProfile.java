package au.com.rayh;

import com.cloudbees.plugins.credentials.BaseCredentials;
import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.CredentialsScope;
import hudson.Extension;
import hudson.util.IOUtils;
import hudson.util.Secret;
import jenkins.security.ConfidentialKey;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Apple developer profile, which consists of any number of PKCS12 of the private key
 * and the certificate for code signing, and mobile provisioning profiles.
 *
 * @author Kohsuke Kawaguchi
 */
public class DeveloperProfile extends BaseCredentials {
    /**
     * Password of the PKCS12 files inside the profile.
     */
    private Secret password;

    /**
     * Random generated unique ID that identifies this developer profile among others.
     */
    private final String id;

    private final String description;

    @DataBoundConstructor
    public DeveloperProfile(String id, String description, Secret password, FileItem image) throws IOException {
        super(CredentialsScope.GLOBAL);
        if (id==null)
            id = UUID.randomUUID().toString();
        this.id = id;
        this.description = description;
        this.password= password;

        if (image != null && !StringUtils.isBlank(image.getName())) {
            // for added secrecy, store this in the confidential store
            new ConfidentialKeyImpl(id).store(image);
        }
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public Secret getPassword() {
        return password;
    }

    /**
     * Retrieves the PKCS12 byte image.
     */
    public byte[] getImage() throws IOException {
        return new ConfidentialKeyImpl(id).load();
    }

    /**
     * Obtains the certificates in this developer profile.
     */
    public @Nonnull List<X509Certificate> getCertificates() throws IOException, GeneralSecurityException {
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(getImage()));
        try {
            List<X509Certificate> r = new ArrayList<X509Certificate>();

            ZipEntry ze;
            while ((ze=zip.getNextEntry())!=null) {
                if (ze.getName().endsWith(".p12")) {
                    KeyStore ks = KeyStore.getInstance("pkcs12");
                    ks.load(zip,password.getPlainText().toCharArray());
                    Enumeration<String> en = ks.aliases();
                    while (en.hasMoreElements()) {
                        String s = en.nextElement();
                        Certificate c = ks.getCertificate(s);
                        if (c instanceof X509Certificate) {
                            r.add((X509Certificate) c);
                        }
                    }
                }
            }

            return r;
        } finally {
            zip.close();
        }
    }

    public String getDisplayNameOf(X509Certificate p) {
        String name = p.getSubjectDN().getName();
        try {
            LdapName n = new LdapName(name);
            for (Rdn rdn : n.getRdns()) {
                if (rdn.getType().equalsIgnoreCase("CN"))
                    return rdn.getValue().toString();
            }
        } catch (InvalidNameException e) {
            // fall through
        }
        return name; // fallback
    }

    @Extension
    public static class DescriptorImpl extends CredentialsDescriptor {
        @Override
        public String getDisplayName() {
            return "Apple Developer Profile";
        }
    }

    static class ConfidentialKeyImpl extends ConfidentialKey {
        ConfidentialKeyImpl(String id) {
            super(DeveloperProfile.class.getName()+"."+id);
        }

        public void store(FileItem submitted) throws IOException {
            super.store(IOUtils.toByteArray(submitted.getInputStream()));
        }

        public @CheckForNull byte[] load() throws IOException {
            return super.load();
        }
    }
}
