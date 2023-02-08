package datadog.trace.common.writer.ddintake;

import datadog.trace.api.WellKnownTags;
import datadog.trace.api.intake.TrackType;
import datadog.trace.civisibility.writer.ddintake.CiTestCycleMapperV2;
import datadog.trace.common.writer.RemoteMapper;
import datadog.trace.common.writer.RemoteMapperDiscovery;

/**
 * Mapper discovery logic when a DDIntake is used. The mapper is discovered based on a backend
 * TrackType. If the TrackType is not supported, it returns a Noop Mapper. Typically, an instance of
 * this class is used during the mapper lazy loading in the {@code PayloadDispatcher} class.
 */
public class DDIntakeMapperDiscovery implements RemoteMapperDiscovery {

  private final TrackType trackType;
  private final WellKnownTags wellKnownTags;

  private RemoteMapper mapper;

  public DDIntakeMapperDiscovery(final TrackType trackType, final WellKnownTags wellKnownTags) {
    this.trackType = trackType;
    this.wellKnownTags = wellKnownTags;
  }

  private void reset() {
    mapper = null;
  }

  @Override
  public void discover() {
    reset();
    if (TrackType.CITESTCYCLE.equals(trackType)) {
      mapper = new CiTestCycleMapperV2(wellKnownTags);
    } else {
      mapper = RemoteMapper.NO_OP;
    }
  }

  @Override
  public RemoteMapper getMapper() {
    return mapper;
  }
}
