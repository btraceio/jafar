package io.jafar.arch.fixture;

/**
 * Fire-test violator for rule (h) (see ArchRuleFireTest): a plain class where the rule requires an
 * interface. Rule (h) aims at real ServiceLoader SPI types, which a fixture cannot make
 * non-interfaces, so the fire test uses the parameterized factory {@code
 * ArchitectureRules.spiTypesRemainInterfaces(List)} with this class's name instead.
 */
public final class NotAnInterfaceSpiFixture {}
