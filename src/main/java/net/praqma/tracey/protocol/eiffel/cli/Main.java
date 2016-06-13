package net.praqma.tracey.protocol.eiffel.cli;

import net.praqma.tracey.protocol.eiffel.EiffelEventFactory;
import net.praqma.tracey.protocol.eiffel.EiffelEventOuterClass.*;
import net.praqma.tracey.protocol.eiffel.EiffelSourceChangeCreatedEventFactory;
import net.praqma.tracey.protocol.eiffel.EiffelSourceChangeCreatedEventOuterClass.*;
import net.praqma.tracey.protocol.eiffel.MetaFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class Main {
    private static final Logger log = Logger.getLogger( Main.class.getName() );

    public static void main (String[] args) throws IOException, ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        EiffelEvent.Meta.Source.Serializer gav = MetaFactory.getSerializer("whatever", "1.0.0", "com.whatever");
        EiffelEvent.Meta.Source source = MetaFactory.getSource("hots", "name", "uri", gav);
        EiffelEvent.Meta meta = MetaFactory.create("domainId", EiffelEvent.Meta.EventType.EiffelSourceChangeCreatedEvent, source);
        final List<EiffelEvent.Link> links = new ArrayList<>();
        links.add(EiffelEvent.Link.newBuilder().setType(EiffelEvent.Link.LinkType.PREVIOUS_VERSION).setId(UUID.randomUUID().toString()).build());
        links.add(EiffelEvent.Link.newBuilder().setType(EiffelEvent.Link.LinkType.CAUSE).setId(UUID.randomUUID().toString()).build());
        final EiffelSourceChangeCreatedEvent data = EiffelSourceChangeCreatedEventFactory.createFromGit(Paths.get(".").toAbsolutePath().normalize().toString(),
                "HEAD", "master");
        final EiffelEvent event = EiffelEventFactory.create(meta, data, links);
        log.info("\n" + event.toString());
    }
}
