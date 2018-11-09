(ns analyze-change-measurements.doo-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [analyze-change-measurements.core-test]))

(doo-tests 'analyze-change-measurements.core-test)

