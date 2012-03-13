(defproject tv.zapps.zippo "1.0.0-SNAPSHOT"
  :description "FIXME: write description"
  :main tv.zapps.zippo.core
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/tools.logging "0.2.3"]
                 [org.clojure/data.codec "0.1.0"]
                 [log4j "1.2.16" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]
                 [org.apache.commons/commons-math3 "3.0"]
                 [clj-yaml "0.3.1"]
                 [cheshire "3.0.0"]
                 [nl.claude.tools "0.0.2"]]
  :dev-dependencies [[swank-clojure "1.4.0"]])
