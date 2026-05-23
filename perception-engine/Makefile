SBT ?= sbt

.PHONY: compile test assembly reality-assembly e2e-healthkit-spezi clean

compile:
	$(SBT) compile

test:
	$(SBT) test

assembly:
	$(SBT) assembly

reality-assembly:
	cd .. && $(SBT) assembly

e2e-healthkit-spezi: reality-assembly assembly
	tests/e2e_healthkit_spezi.sh

clean:
	$(SBT) clean
