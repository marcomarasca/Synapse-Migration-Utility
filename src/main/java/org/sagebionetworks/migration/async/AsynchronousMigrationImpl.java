package org.sagebionetworks.migration.async;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;

import org.sagebionetworks.migration.AsyncMigrationException;
import org.sagebionetworks.migration.config.Configuration;
import org.sagebionetworks.migration.utils.TypeToMigrateMetadata;
import org.sagebionetworks.util.Clock;

import com.google.inject.Inject;

/**
 * This algorithm is driven by jobs that need to be executed on the destination.
 * New destination jobs will be started as long as the maximum number of concurrent
 * destination jobs is not exceeded.  Sleep is used to wait for active jobs to terminate
 * when the  maximum number of active jobs is reached.
 */
public class AsynchronousMigrationImpl implements AsynchronousMigration {
	
	private static final long SLEEP_TIME_MS = 2000L;
	
	Configuration config;
	DestinationJobBuilder jobBuilder;
	DestinationJobExecutor jobExecutor;
	Clock clock;
	
	@Inject
	public AsynchronousMigrationImpl(Configuration config, DestinationJobBuilder jobBuilder,
			DestinationJobExecutor jobExecutor, Clock clock) {
		super();
		this.config = config;
		this.jobBuilder = jobBuilder;
		this.jobExecutor = jobExecutor;
		this.clock = clock;
	}

	/*
	 * (non-Javadoc)
	 * @see org.sagebionetworks.migration.async.AsynchronousMigration#migratePrimaryTypes(java.util.List)
	 */
	@Override
	public void migratePrimaryTypes(List<TypeToMigrateMetadata> primaryTypes) {
		AsyncMigrationException lastException = null;
		// Tracks the active destination jobs.
		List<Future<?>> activeDestinationJobs = new LinkedList<>();
		// Start generating jobs to push to the destination.
		Iterator<DestinationJob> jobIterator = jobBuilder.buildDestinationJobs(primaryTypes);
		while(jobIterator.hasNext()) {
			DestinationJob nextJob = jobIterator.next();
			// Start this job on the destination.
			activeDestinationJobs.add(jobExecutor.startDestinationJob(nextJob));
			// wait for the number of active destination jobs to be under the max
			while(activeDestinationJobs.size() >= config.getMaximumNumberOfDestinationJobs()) {
				// give the jobs some time to complete
				sleep();
				try {
					// remove any completed jobs
					removeTerminatedJobs(activeDestinationJobs);
				} catch (AsyncMigrationException e) {
					lastException = e;
				}
			}
		}
		if(lastException != null) {
			// throwing this exception signals another migration run is required.
			throw lastException;
		}
	}

	/**
	 * Sleep for two seconds.
	 */
	private void sleep() {
		try {
			clock.sleep(SLEEP_TIME_MS);
		} catch (InterruptedException e1) {
			// interrupt will trigger failure.
			throw new RuntimeException(e1);
		}
	}

	/**
	 * Remove any job that has terminated either with success or failure.
	 * @param lastException
	 * @param activeDestinationJobs
	 * @return
	 */
	void removeTerminatedJobs(List<Future<?>> activeDestinationJobs) {
		// find an remove any completed jobs.
		Iterator<Future<?>> futureIterator = activeDestinationJobs.iterator();
		while(futureIterator.hasNext()) {
			Future<?> jobFuture = futureIterator.next();
			if(jobFuture.isDone()) {
				// unconditionally remove this job from the list.
				futureIterator.remove();
				try {
					// call get to determine if the job succeeded.
					jobFuture.get();
				}catch(AsyncMigrationException e) {
					// Indicates that another migration run is required without terminating this run.
					throw e;
				} catch (Exception e) {
					// any other type of exception will terminate the migration.
					throw new RuntimeException(e);
				}
			}
		}
	}
	
}
