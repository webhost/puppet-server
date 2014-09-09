(ns puppetlabs.services.jruby.testutils
  (:import (com.puppetlabs.puppetserver JRubyPuppet JRubyPuppetResponse))
  (:require [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [puppetlabs.services.puppet-profiler.puppet-profiler-core :as profiler-core]
            [me.raynes.fs :as fs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants

(def prod-pool-descriptor {:environment :production})

(def ruby-load-path ["./ruby/puppet/lib" "./ruby/facter/lib"])

(def conf-dir "./target/master-conf")

(def gem-home "./target/jruby-gem-home")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JRubyPuppet Test fixtures

(defn with-puppet-conf
  "This function returns a test fixture that will copy a specified puppet.conf
  file into the appropriate location for testing, and then delete it after the
  tests have completed."
  [puppet-conf-file]
  (let [target-path (fs/file conf-dir "puppet.conf")]
    (fn [f]
      (fs/copy+ puppet-conf-file target-path)
      (try
        (f)
        (finally
          (fs/delete target-path))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JRubyPuppet Test util functions

(defn jruby-puppet-tk-config
  "Create a JRubyPuppet pool config which includes the provided list of pool
  descriptions.  Suitable for use in bootstrapping trapperkeeper."
  [pools-list]
  {:os-settings {:ruby-load-path ruby-load-path}
   :jruby-puppet {:gem-home        gem-home
                  :master-conf-dir conf-dir
                  :jruby-pools     pools-list}})

(defn jruby-puppet-config
  "Create a JRubyPuppet pool config which includes the provided list of pool
  descriptions.  Suitable for use when calling jruby service functions directly."
  [pools-list]
  {:ruby-load-path  ruby-load-path
   :gem-home        gem-home
   :master-conf-dir conf-dir
   :jruby-pools     pools-list})

(def default-config-no-size
  (jruby-puppet-config [{:environment "production"}]))

(def default-profiler
  nil)

(defn jruby-puppet-config-with-prod-env
  "Create some settings used for creating a JRubyPuppet pool via
  `create-jruby-pool`.  Suitable for use when calling jruby service functions
  directly."
  ([] (jruby-puppet-config-with-prod-env 1))
  ([size]
   (jruby-puppet-config [{:environment "production"
                          :size        size}])))

(defn jruby-puppet-tk-config-with-prod-env
  "Create some settings used for creating a JRubyPuppet pool via
  `create-jruby-pool`.  Suitable for use in bootstrapping trapperkeeper."
  ([] (jruby-puppet-tk-config-with-prod-env 1))
  ([size]
   (jruby-puppet-tk-config [{:environment "production"
                             :size        size}])))


(defn jruby-puppet-config-with-prod-test-env
  "Create a settings structure which contains a `production` environment of a
  given size as well as an environment named `test` of a given size."
  [prod-size test-size]
  (jruby-puppet-config [{:environment "production"
                         :size prod-size}
                        {:environment "test"
                         :size test-size}]))

(defn create-jruby-instance
  ([]
   (create-jruby-instance (jruby-puppet-config-with-prod-env 1)))
  ([config]
   (jruby-core/create-jruby-instance config default-profiler)))

(defn create-mock-jruby-instance
  "Creates a mock implementation of the JRubyPuppet interface."
  [& _]
  (reify JRubyPuppet
    (handleRequest [this request]
      (JRubyPuppetResponse. 0 nil nil nil))
    (getSetting [this setting]
      (Object.))))

(defn mock-jruby-fixture
  "Test fixture which changes the behavior of the JRubyPool to create
  mock JRubyPuppet instances."
  [f]
  (with-redefs
    [jruby-core/create-jruby-instance create-mock-jruby-instance]
    (f)))