(ns puppetlabs.master.certificate-authority
  (:require [me.raynes.fs :as fs]
            [schema.core :as schema]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.certificate-authority.core :as utils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def MasterFilePaths
  "Paths to the various directories and files within the SSL directory,
  excluding the CA directory and its contents. These are only used during
  initialization of the master. All of these are Puppet configuration settings."
  {:requestdir  String
   :certdir     String
   :hostcert    String
   :localcacert String
   :hostprivkey String
   :hostpubkey  String})

(def CaSettings
  "Settings from Puppet that are necessary for CA initialization and request
  handling during normal Puppet operation.
  Most of these are Puppet configuration settings."
  {:autosign  (schema/either String Boolean)
   :cacert    String
   :cacrl     String
   :cakey     String
   :capub     String
   :ca-name   String
   :ca-ttl    schema/Int
   :csrdir    String
   :load-path [String]
   :signeddir String
   :serial    String})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(schema/defn settings->cadir-paths
  "Trim down the CA settings to include only paths to files and directories.
  These paths are necessary during CA initialization for determining what needs
  to be created and where they should be placed."
  [ca-settings :- CaSettings]
  (dissoc ca-settings :autosign :ca-ttl :ca-name :load-path))

(defn path-to-cert
  "Return a path to the `subject`s certificate file under the `signeddir`."
  [signeddir subject]
  (str signeddir "/" subject ".pem"))

(defn path-to-cert-request
  "Return a path to the `subject`s certificate request file under the `csrdir`."
  [csrdir subject]
  (str csrdir "/" subject ".pem"))

(defn files-exist?
  "Predicate to test whether all of the files exist on disk.
  `paths` is expected to be a map with file path values,
  such as `ssldir-file-paths` or `ca-settings`."
  [paths]
  {:pre [(map? paths)]}
  (every? fs/exists? (vals paths)))

(defn create-parent-directories!
  "Create all intermediate directories present in each of the file paths.
  Throws an exception if the directory cannot be created."
  [paths]
  {:pre [(sequential? paths)]}
  (doseq [path paths]
    (ks/mkdirs! (fs/parent path))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Serial number functions + lock
(def serial-number-file-lock
  "The lock used to prevent concurrent access to the serial number file."
  (new Object))

(defn parse-serial-number
  "Parses a serial number from its format on disk.  See `format-serial-number`
  for the awful, gory details."
  [serial-number]
  {:post [(integer? %)]}
  (read-string (str "0x" serial-number)))

(defn get-serial-number!
  "Reads the serial number file from disk and returns the serial number.
  If the file does not exist, it will be created, and the serial number will be 1."
  [serial-number-file]
  (if (fs/exists? serial-number-file)
    (-> serial-number-file
        (slurp)
        (.trim)
        (parse-serial-number))
    (do
      (create-parent-directories! [serial-number-file])
      (fs/create (fs/file serial-number-file))
      1)))

(defn format-serial-number
  "Converts a serial number to the format it needs to be written in on disk.
  This function has to write serial numbers in the same format that the puppet
  ruby code does, to maintain compatibility with things like 'puppet cert';
  for whatever arcane reason, that format is 0-padding up to 4 digits.

  (format '%04X' 42)
  => '002A'"
  [serial-number]
  {:pre [(integer? serial-number)]}
  (format "%04X" serial-number))

(defn next-serial-number!
  "Returns the next serial number to be used when signing a certificate request.
  Reads the serial number as a hex value from the given file and replaces the
  contents of `serial-number-file` with the next serial number for a subsequent
  call.  Puppet's 'serial' setting defines the location of the serial number file."
  [serial-number-file]
  (locking serial-number-file-lock
    (let [serial-number (get-serial-number! serial-number-file)]
      (spit serial-number-file (format-serial-number (inc serial-number)))
      serial-number)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Initialization
(schema/defn initialize-ca!
  "Given the CA settings, generate and write to disk all of the necessary
  SSL files for the CA. Any existing files will be replaced."
  [ca-settings :- CaSettings
   keylength :- schema/Int]
  {:post [(files-exist? (settings->cadir-paths ca-settings))]}
  (log/debug (str "Initializing SSL for the CA; settings:\n"
                  (ks/pprint-to-string ca-settings)))
  (create-parent-directories! (vals (settings->cadir-paths ca-settings)))
  (-> ca-settings :csrdir fs/file ks/mkdirs!)
  (-> ca-settings :signeddir fs/file ks/mkdirs!)
  (let [keypair     (utils/generate-key-pair keylength)
        public-key  (.getPublic keypair)
        private-key (.getPrivate keypair)
        x500-name   (utils/generate-x500-name (:ca-name ca-settings))
        serial-file (:serial ca-settings)
        cacert      (-> (utils/generate-certificate-request keypair x500-name)
                        (utils/sign-certificate-request
                          x500-name
                          (next-serial-number! serial-file)
                          private-key))
        cacrl       (-> cacert
                        .getIssuerX500Principal
                        (utils/generate-crl private-key))]
    (utils/key->pem! public-key (:capub ca-settings))
    (utils/key->pem! private-key (:cakey ca-settings))
    (utils/cert->pem! cacert (:cacert ca-settings))
    (utils/crl->pem! cacrl (:cacrl ca-settings))))

(schema/defn initialize-master!
  "Given the SSL directory file paths, master certname, and CA information,
  generate and write to disk all of the necessary SSL files for the master.
  Any existing files will be replaced."
  [ssldir-file-paths :- MasterFilePaths
   master-certname :- String
   ca-name :- String
   ca-private-key :- (schema/pred utils/private-key?)
   ca-cert :- (schema/pred utils/certificate?)
   keylength :- schema/Int
   serial-number-file :- String]
  {:post [(files-exist? ssldir-file-paths)]}
  (log/debug (str "Initializing SSL for the Master; file paths:\n"
                  (ks/pprint-to-string ssldir-file-paths)))
  (create-parent-directories! (vals ssldir-file-paths))
  (-> ssldir-file-paths :certdir fs/file ks/mkdirs!)
  (-> ssldir-file-paths :requestdir fs/file ks/mkdirs!)
  (let [keypair      (utils/generate-key-pair keylength)
        public-key   (.getPublic keypair)
        private-key  (.getPrivate keypair)
        x500-name    (utils/generate-x500-name master-certname)
        ca-x500-name (utils/generate-x500-name ca-name)
        hostcert     (-> (utils/generate-certificate-request keypair x500-name)
                         (utils/sign-certificate-request
                           ca-x500-name
                           (next-serial-number! serial-number-file)
                           ca-private-key))]
    (utils/key->pem! public-key (:hostpubkey ssldir-file-paths))
    (utils/key->pem! private-key (:hostprivkey ssldir-file-paths))
    (utils/cert->pem! hostcert (:hostcert ssldir-file-paths))
    (utils/cert->pem! ca-cert (:localcacert ssldir-file-paths))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Autosign

(schema/defn glob-matches? :- schema/Bool
  "Test if a subject matches the domain-name glob from the autosign whitelist.

   The glob is expected to start with a '*' and be in a form like `*.foo.bar`.
   The subject is expected to contain only lowercase characters and be in a
   form like `agent.foo.bar`. Capitalization in the glob will be ignored.

   Examples:
     (glob-matches? *.foo.bar agent.foo.bar) => true
     (glob-matches? *.baz baz) => true
     (glob-matches? *.QUX 0.1.qux) => true"
  [glob :- String
   subject :- String]
  (letfn [(munge [name]
            (-> name
                str/lower-case
                (str/split #"\.")
                reverse))
          (seq-starts-with? [a b]
            (= b (take (count b) a)))]
    (seq-starts-with? (munge subject)
                      (butlast (munge glob)))))

(schema/defn line-matches? :- schema/Bool
  "Test if the subject matches the line from the autosign whitelist.
   The line is expected to be an exact certname or a domain-name glob.
   A single line with the character '*' will match all subjects.
   If the line contains invalid characters it will be logged and
   false will be returned."
  [whitelist :- String
   subject :- String
   line :- String]
  (if (or (.contains line "#") (.contains line " "))
    (do (log/errorf "Invalid pattern '%s' found in %s" line whitelist)
        false)
    (if (= line "*")
      true
      (if (.startsWith line "*")
        (glob-matches? line subject)
        (= line subject)))))

(schema/defn whitelist-matches? :- schema/Bool
  "Test if the whitelist file contains an entry that matches the subject.
   Each line of the file is expected to contain a single entry, either as
   an exact certname or a domain-name glob, and will be evaluated verbatim.
   All blank lines and comment lines (starting with '#') will be ignored.
   If an invalid pattern is encountered, it will be logged and ignored."
  [whitelist :- String
   subject :- String]
  (with-open [r (io/reader whitelist)]
    (not (nil? (some (partial line-matches? whitelist subject)
                     (remove #(or (.startsWith % "#")
                                  (str/blank? %))
                             (line-seq r)))))))

(schema/defn execute-autosign-command!
  :- {:out (schema/maybe String) :err (schema/maybe String) :exit schema/Int}
  "Execute the autosign script and return a map containing the standard-out,
   standard-err, and exit code. The subject will be passed in as input, and
   the CSR stream will be provided on standard-in. The load-path will be
   prepended to the RUBYLIB found in the environment, and is intended to make
   the Puppet and Facter Ruby libraries available to the autosign script.
   All output (stdout & stderr) will be logged at the debug level."
  [executable :- String
   subject :- String
   csr-fn :- (schema/pred fn?)
   load-path :- [String]]
  (log/debugf "Executing '%s %s'" executable subject)
  (let [env     (into {} (System/getenv))
        rubylib (->> (if-let [lib (get env "RUBYLIB")]
                       (cons lib load-path)
                       load-path)
                     (map fs/absolute-path)
                     (str/join (System/getProperty "path.separator")))
        results (shell/sh executable subject
                          :in (csr-fn)
                          :env (merge env {:RUBYLIB rubylib}))]
    (log/debugf "Autosign command '%s %s' exit status: %d"
                executable subject (:exit results))
    (log/debugf "Autosign command '%s %s' output: %s"
                executable subject (str (:err results) (:out results)))
    results))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  config->settings :- CaSettings
  "Given the configuration map from the JVM Puppet config
   service return a map with of all the CA settings."
  [{:keys [jvm-puppet jruby-puppet]}]
  (-> (select-keys jvm-puppet (keys CaSettings))
      (assoc :load-path (:load-path jruby-puppet))))

(schema/defn ^:always-validate
  get-certificate :- (schema/maybe String)
  "Given a subject name and paths to the certificate directory and the CA
  certificate, return the subject's certificate as a string, or nil if not found.
  If the subject is 'ca', then use the `cacert` path instead."
  [subject :- String
   cacert :- String
   signeddir :- String]
  (let [cert-path (if (= "ca" subject)
                    cacert
                    (path-to-cert signeddir subject))]
    (if (fs/exists? cert-path)
      (slurp cert-path))))

(schema/defn ^:always-validate
  get-certificate-request :- (schema/maybe String)
  "Given a subject name, return their certificate request as a string, or nil if
  not found.  Looks for certificate requests in `csrdir`."
  [subject :- String
   csrdir :- String]
  (let [cert-request-path (path-to-cert-request csrdir subject)]
    (if (fs/exists? cert-request-path)
      (slurp cert-request-path))))

(schema/defn ^:always-validate
  autosign-csr? :- schema/Bool
  "Return true if the CSR should be automatically signed given
  Puppet's autosign setting, and false otherwise."
  [autosign :- (schema/either String schema/Bool)
   subject :- String
   csr-fn :- (schema/pred fn?)
   load-path :- [String]]
  (if (ks/boolean? autosign)
    autosign
    (if (fs/exists? autosign)
      (if (fs/executable? autosign)
        (-> (execute-autosign-command! autosign subject csr-fn load-path)
            :exit
            zero?)
        (whitelist-matches? autosign subject))
      false)))

(schema/defn ^:always-validate
  autosign-certificate-request!
  "Given a subject name, their certificate request, and the CA settings
  from Puppet, auto-sign the request and write the certificate to disk."
  [subject :- String
   csr-fn :- (schema/pred fn?)
   {:keys [ca-name cakey signeddir ca-ttl serial]}]
  ;; TODO PE-3173 calculate cert expiration based on ca-ttl and the CSR
  ;;              issue date and pass to utils/sign-certificate-request
  (let [signed-cert (utils/sign-certificate-request
                      (utils/pem->csr (csr-fn))
                      (utils/generate-x500-name ca-name)
                      (next-serial-number! serial)
                      (utils/pem->private-key cakey))]
    (utils/cert->pem! signed-cert (path-to-cert signeddir subject))))

(schema/defn ^:always-validate
  save-certificate-request!
  "Write the subject's certificate request to disk under the CSR directory."
  [subject :- String
   csr-fn :- (schema/pred fn?)
   csrdir :- String]
  (-> (utils/pem->csr (csr-fn))
      (utils/obj->pem! (path-to-cert-request csrdir subject))))

(schema/defn ^:always-validate
  get-certificate-revocation-list :- String
  "Given the value of the 'cacrl' setting from Puppet,
  return the CRL from the .pem file on disk."
  [cacrl :- String]
  (slurp cacrl))

(schema/defn ^:always-validate
  initialize!
  "Given the CA settings, master file paths, and the master's certname,
  prepare all necessary SSL files for the master and CA.
  If all of the necessary SSL files exist, new ones will not be generated."
  ([ca-settings master-file-paths master-certname]
    (initialize! ca-settings
                 master-file-paths
                 master-certname
                 utils/default-key-length))
  ([ca-settings       :- CaSettings
    master-file-paths :- MasterFilePaths
    master-certname   :- String
    keylength         :- schema/Int]
    (if (files-exist? (settings->cadir-paths ca-settings))
      (log/info "CA already initialized for SSL")
      (initialize-ca! ca-settings keylength))
    (if (files-exist? master-file-paths)
      (log/info "Master already initialized for SSL")
      (let [cakey  (-> ca-settings :cakey utils/pem->private-key)
            cacert (-> ca-settings :cacert utils/pem->cert)
            caname (:ca-name ca-settings)
            serial-number-file (:serial ca-settings)]
        (initialize-master!
          master-file-paths
          master-certname
          caname
          cakey
          cacert
          keylength
          serial-number-file)))))
