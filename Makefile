# Replay every proof in this repository with GNATprove.
GNATPROVE ?= gnatprove
JOBS      ?= 4

.PHONY: prove clean

prove:
	cd proof && $(GNATPROVE) -P proof.gpr --level=2 --report=fail -j$(JOBS)
	sed -n '/^SPARK Analysis results/,/^Total/p' proof/obj/gnatprove/gnatprove.out

clean:
	rm -rf proof/obj
