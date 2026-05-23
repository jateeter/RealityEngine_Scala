SBT ?= sbt

.PHONY: compile test assembly e2e-healthkit-spezi clean

compile:
	$(SBT) compile

test:
	$(SBT) test

assembly:
	$(SBT) assembly

e2e-healthkit-spezi: assembly
	tests/e2e_healthkit_spezi.sh

clean:
	$(SBT) clean
