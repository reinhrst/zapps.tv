(defproject tv.zapps.purkinje "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :main tv.zapps.purkinje.core
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/tools.logging "0.2.3"]
                 [log4j "1.2.16" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]
                 [jtransforms "2.4"]
                 [nl.claude.tools "0.0.2"]]
  :jvm-opts ["-Xmx1g"]               ; at least needed for protocol_matcher_data_generated
  :dev-dependencies [[swank-clojure "1.4.0"]])
