package de.ialistannen.lighthouse.updater;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.model.LighthouseImageUpdate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DockerUpdater {

  private static final Logger LOGGER = LoggerFactory.getLogger(DockerUpdater.class);

  private final DockerClient client;
  private final List<Bind> updaterMounts;
  private final String updaterEntrypoint;
  private final String updaterDockerImage;

  public DockerUpdater(
    DockerClient client,
    List<String> updaterMounts,
    String updaterEntrypoint,
    String updaterDockerImage
  ) {
    this.client = client;
    this.updaterMounts = mountsToBinds(updaterMounts);
    this.updaterEntrypoint = updaterEntrypoint;
    this.updaterDockerImage = updaterDockerImage;
  }

  public void updateBaseImage(LighthouseImageUpdate update) throws InterruptedException {
    LOGGER.info("Updating base image {} for {}", update.getNameWithTag(), update.sourceImageNames());
    client.pullImageCmd(update.imageName())
      .withTag(update.tag())
      .exec(new PullImageResultCallback())
      .awaitCompletion(5, TimeUnit.MINUTES);
  }

  public void rebuildContainers(List<LighthouseContainerUpdate> updates) throws InterruptedException {
    LOGGER.info("Rebuilding {} containers", updates.size());
    Set<LighthouseImageUpdate> imageUpdates = updates.stream()
      .map(LighthouseContainerUpdate::imageUpdate)
      .collect(Collectors.toSet());

    for (LighthouseImageUpdate update : imageUpdates) {
      updateBaseImage(update);
    }

    List<String> command = updates.stream()
      .map(LighthouseContainerUpdate::names)
      .flatMap(Collection::stream)
      .distinct()
      .collect(Collectors.toCollection(ArrayList::new));
    command.add(0, updaterEntrypoint);

    CreateContainerResponse containerResponse = client.createContainerCmd(updaterDockerImage)
      .withHostConfig(
        HostConfig.newHostConfig().withBinds(updaterMounts)
      )
      .withCmd(command)
      .exec();
    LOGGER.info("Started updater has ID {}", containerResponse.getId());

    client.startContainerCmd(containerResponse.getId())
      .exec();

    WaitContainerResultCallback result = client.waitContainerCmd(containerResponse.getId())
      .exec(new WaitContainerResultCallback());

    Instant end = Instant.now().plus(10, ChronoUnit.MINUTES);
    while (Instant.now().isBefore(end)) {
      try {
        int statusCode = result.awaitStatusCode(5, TimeUnit.MINUTES);

        if (statusCode != 0) {
          LOGGER.warn("Rebuild failed with exit code {}", statusCode);
        }

        client.removeContainerCmd(containerResponse.getId()).exec();
        break;
      } catch (DockerClientException ignored) {
      }
    }
  }

  private static List<Bind> mountsToBinds(List<String> updaterMounts) {
    return updaterMounts.stream().map(DockerUpdater::mountToBind).toList();
  }

  private static Bind mountToBind(String mount) {
    String[] parts = mount.split(":");
    if (parts.length != 2) {
      throw new IllegalArgumentException("Mount '" + mount + "' did not conform to 'source:dest' format.");
    }
    return new Bind(parts[0], new Volume(parts[1]));
  }
}
