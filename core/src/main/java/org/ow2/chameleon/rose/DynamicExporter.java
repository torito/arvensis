package org.ow2.chameleon.rose;

import static org.osgi.framework.Constants.OBJECTCLASS;
import static org.osgi.framework.FrameworkUtil.createFilter;
import static org.ow2.chameleon.rose.ExporterService.ENDPOINT_CONFIG_PREFIX;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.LogService;
import org.osgi.service.remoteserviceadmin.ExportReference;
import org.osgi.service.remoteserviceadmin.ExportRegistration;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.ow2.chameleon.rose.internal.BadExportRegistration;

/**
 * A {@link DynamicExporter} allows to export all services matching a given
 * filter with all available {@link ExporterService} dynamically. Basically, an
 * endpoint is created for each services matching the given filter which are
 * available on the gateway for each {@link ExporterService} available. If the
 * service is no more available then all his endpoints are destroyed.
 * 
 * @author barjo
 */
public class DynamicExporter {
	private static final String DEFAULT_EXPORTER_FILTER = "(" + OBJECTCLASS
			+ "=" + ExporterService.class.getName() + ")";

	private final ExporterTracker extracker;
	private final BundleContext context;
	private final Filter sfilter;
	private final Filter xfilter;
	private final Map<String, Object> extraProperties;
	private final DynamicExporterCustomizer customizer;

	private DynamicExporter(Builder builder) {
		extraProperties = builder.extraProperties;
		context = builder.context;
		sfilter = builder.sfilter;
		xfilter = builder.xfilter;
		customizer = builder.customizer;

		extracker = new ExporterTracker();
	}

	/**
	 * Start the dynamic exporter.
	 */
	public void start() {
		extracker.open();
	}

	/**
	 * Stop the dynamic exporter.
	 */
	public void stop() {
		extracker.close();
	}

	/**
	 * @return The {@link ExportReference} created through this
	 *         {@link DynamicExporter}.
	 */
	public ExportReference[] getExportedReference() {
		return customizer.getExportReferences();
	}

	/**
	 * Convenient Builder for the creation of a {@link DynamicExporter}.
	 * 
	 * @author barjo
	 */
	public static class Builder {
		// required
		private final BundleContext context;
		private final Filter sfilter;

		// optional
		private Filter xfilter = createFilter(DEFAULT_EXPORTER_FILTER);
		private Map<String, Object> extraProperties = new HashMap<String, Object>();
		private DynamicExporterCustomizer customizer = new DefautCustomizer();

		public Builder(BundleContext pContext, String serviceFilter)
				throws InvalidSyntaxException {
			sfilter = createFilter(serviceFilter);
			context = pContext;
		}

		public Builder protocol(List<String> protocols)
				throws InvalidSyntaxException {
			StringBuilder sb = new StringBuilder("(&");
			sb.append(xfilter.toString());
			sb.append("(|");
			for (String string : protocols) {
				sb.append("(");
				sb.append(ENDPOINT_CONFIG_PREFIX);
				sb.append("=");
				sb.append(string);
				sb.append(")");
			}
			sb.append("))");
			xfilter = createFilter(sb.toString());
			return this;
		}

		public Builder exporterFilter(String val) throws InvalidSyntaxException {
			StringBuilder sb = new StringBuilder("(&");
			sb.append(xfilter.toString());
			sb.append(val);
			sb.append(")");
			xfilter = createFilter(sb.toString());
			return this;
		}

		public Builder extraProperties(Map<String, Object> val) {
			extraProperties.putAll(val);
			return this;
		}

		public Builder customizer(DynamicExporterCustomizer val) {
			customizer = val;
			return this;
		}

		public DynamicExporter build() {
			return new DynamicExporter(this);
		}
	}

	/**
	 * Track All {@link ExporterService} matching <code>xfilter</code> and
	 * create a {@link ServiceToBeExportedTracker} tracker for each of them.
	 * 
	 * @author barjo
	 */
	private class ExporterTracker implements ServiceTrackerCustomizer {
		private final ServiceTracker tracker;

		private ExporterTracker() {
			tracker = new ServiceTracker(context, xfilter, this);
		}

		private void open() {
			tracker.open();
		}

		private void close() {
			tracker.close();
		}

		public Object addingService(ServiceReference reference) {
			ExporterService exporter = (ExporterService) context
					.getService(reference);
			return new ServiceToBeExportedTracker(exporter);
		}

		public void modifiedService(ServiceReference reference, Object object) {
			// nothing to do

		}

		public void removedService(ServiceReference reference, Object object) {
			ServiceToBeExportedTracker stracker = (ServiceToBeExportedTracker) object;
			stracker.close(); // close the tracker
		}
	}

	/**
	 * Track All service matching <code>sfilter</code> and export them with the
	 * {@link ExporterService} given in the constructor
	 * 
	 * @author barjo
	 */
	private class ServiceToBeExportedTracker implements
			ServiceTrackerCustomizer {
		private final ExporterService exporter;
		private final ServiceTracker tracker;

		private ServiceToBeExportedTracker(ExporterService pExporter) {
			exporter = pExporter;
			tracker = new ServiceTracker(context, sfilter, this);
			tracker.open();
		}

		private void close() {
			tracker.close();
		}

		public Object addingService(ServiceReference reference) {
			Object returnObject = customizer.export(exporter, reference,
					extraProperties);
			//check if got @BadExportRegistration
			if (returnObject instanceof BadExportRegistration) {
				ServiceReference sref = context
						.getServiceReference(LogService.class.getName());
				if (sref != null) {
					((LogService) context.getService(sref)).log(
							LogService.LOG_WARNING,
							((BadExportRegistration) returnObject)
									.getException().getMessage());
					context.ungetService(sref);
				}
			}
			return returnObject;
		}

		public void modifiedService(ServiceReference reference, Object object) {
			// XXX not supported
		}

		public void removedService(ServiceReference reference, Object object) {
			customizer.unExport(exporter, reference, object);
		}
	}

	/**
	 * Default {@link DynamicExporterCustomizer}.
	 * 
	 * @author barjo
	 */
	private static class DefautCustomizer implements DynamicExporterCustomizer {
		private final ConcurrentLinkedQueue<ExportReference> xrefs = new ConcurrentLinkedQueue<ExportReference>();

		public ExportRegistration export(ExporterService exporter,
				ServiceReference sref, Map<String, Object> properties) {
			ExportRegistration registration = exporter.exportService(sref,
					properties);
			// Check if returned registration is @BadExportRegistration
			if (registration instanceof BadExportRegistration) {
				return registration;
			}
			xrefs.add(registration.getExportReference());
			return registration;
		}

		public void unExport(ExporterService exporter, ServiceReference sref,
				Object registration) {
			ExportRegistration reg = (ExportRegistration) registration;
			xrefs.remove(reg.getExportReference());
			reg.close();
		}

		public ExportReference[] getExportReferences() {
			return (ExportReference[]) xrefs.toArray();
		}
	}

}
