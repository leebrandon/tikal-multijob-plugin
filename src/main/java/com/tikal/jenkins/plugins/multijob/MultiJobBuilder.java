package com.tikal.jenkins.plugins.multijob;

import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.console.HyperlinkNote;
import hudson.model.Action;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.DependecyDeclarer;
import hudson.model.DependencyGraph;
import hudson.model.DependencyGraph.Dependency;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause.UpstreamCause;
import hudson.model.Hudson;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;
import org.jenkinsci.plugins.envinject.EnvInjectBuilderContributionAction;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

public class MultiJobBuilder extends Builder implements DependecyDeclarer {

	private String phaseName;
	public boolean syncBuildNumbers;
	private List<PhaseJobsConfig> phaseJobs;
	private ContinuationCondition continuationCondition = ContinuationCondition.SUCCESSFUL;

	@DataBoundConstructor
	public MultiJobBuilder(String phaseName, boolean syncBuildNumbers, List<PhaseJobsConfig> phaseJobs,
			ContinuationCondition continuationCondition) {
		this.phaseName = phaseName;
		this.syncBuildNumbers = syncBuildNumbers;
		this.phaseJobs = Util.fixNull(phaseJobs);
		this.continuationCondition = continuationCondition;
	}

	@Override
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		Hudson hudson = Hudson.getInstance();
		MultiJobBuild thisBuild = (MultiJobBuild) build;
		int parentBuildNumber = thisBuild.getNumber();							// Grab the parent build number
		MultiJobProject thisProject = thisBuild.getProject();
		Map<AbstractProjectKey, PhaseJobsConfig> projects = new HashMap<AbstractProjectKey, PhaseJobsConfig>(
				phaseJobs.size());

		for (PhaseJobsConfig project : phaseJobs) {
			TopLevelItem item = hudson.getItem(project.getJobName());
			if (item instanceof AbstractProject) {
				AbstractProject job = (AbstractProject) item;
				projects.put(new AbstractProjectKey(job), project);
			}
		}

		List<Future<Build>> futuresList = new ArrayList<Future<Build>>();
		List<AbstractProject> projectList = new ArrayList<AbstractProject>();
		for (AbstractProjectKey projectKey : projects.keySet()) {
			AbstractProject project = projectKey.getProject();

			listener.getLogger().printf(
					"Starting build job %s.\n",
					HyperlinkNote.encodeTo('/' + project.getUrl(),
							project.getFullName()));

			PhaseJobsConfig projectConfig = projects.get(projectKey);			
			
			// Set subjob build number the same as the parent build number, if syncBuildNumbers checkbox is true
			if( syncBuildNumbers )
			{
				// Make sure next build number is lower
				if( project.getNextBuildNumber() > parentBuildNumber ) {
					listener.getLogger().printf("Warning: " + project.getName() + " build number is higher than parents build number\n");
					listener.getLogger().printf("Warning: " + project.getName() + " build number must be corrected manually\n");
					listener.getLogger().printf("Warning: Build numbers not synchronized\n");
				}
				else {
					listener.getLogger().printf("Synchronizing build number for " + project.getName() + "\n");
					project.updateNextBuildNumber(parentBuildNumber);
				}
			}
			
			List<Action> actions = new ArrayList<Action>();
			prepareActions(build, project, projectConfig, listener, actions);
			Future future = project.scheduleBuild2(project.getQuietPeriod(),
					new UpstreamCause((Run) build),
					actions.toArray(new Action[0]));
			if (future != null) {
				futuresList.add(future);
				projectList.add(project);
			}
			// Wait a second before next build start.
				Thread.sleep(1000);
		}

		boolean failed = false;
		boolean canContinue = true;
		while (!futuresList.isEmpty() && !failed) {
			for (Future future : futuresList) {
				AbstractProject project = projectList.get(futuresList
						.indexOf(future));
				if (future.isDone()) {
					try {
						AbstractBuild jobBuild = (AbstractBuild) future.get();
						Result result = jobBuild.getResult();
						ChangeLogSet<Entry> changeLogSet = jobBuild
								.getChangeSet();
						if (changeLogSet != null) {
							((MultiJobBuild) build)
									.addChangeLogSet(changeLogSet);
						}
						listener.getLogger().println(
								"Finished Build : "
										+ HyperlinkNote.encodeTo(
												"/" + jobBuild.getUrl() + "/",
												String.valueOf(jobBuild
														.getDisplayName()))
										+ " of Job : "
										+ HyperlinkNote.encodeTo('/' + jobBuild
												.getProject().getUrl(),
												jobBuild.getProject()
														.getFullName())
										+ " with status :"
										+ HyperlinkNote.encodeTo(
												'/' + jobBuild.getUrl()
														+ "/console/",
												result.toString()));
						if (!continuationCondition.isContinue(jobBuild)) {
							failed = true;
						}
						addSubBuild(thisBuild, thisProject, jobBuild);
						addBuildEnvironmentVariables(thisBuild, jobBuild);
						projectList.remove(project);
						futuresList.remove(future);
						break;
					} catch (ExecutionException e) {
						failed = true;
					}
				} else if (project.isBuilding()) {
					addSubBuild(thisBuild, thisProject,
							(AbstractBuild) project.getLastBuild());
				}
			}
			// Wait a second before next check.
			try{
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				for(Future<Build> future : futuresList)
					future.cancel(true);
				throw new InterruptedException();
			}
		}
		if (failed) {
			for (Future future : futuresList)
				future.cancel(true);
		}
		canContinue = !failed;
		return canContinue;
	}

	@SuppressWarnings("rawtypes")
	private void addSubBuild(MultiJobBuild thisBuild,
			MultiJobProject thisProject, AbstractBuild jobBuild) {
		thisBuild.addSubBuild(thisProject.getName(), thisBuild.getNumber(),
				jobBuild.getProject().getName(), jobBuild.getNumber(),
				phaseName, jobBuild);
	}

	@SuppressWarnings("rawtypes")
	private void addBuildEnvironmentVariables(MultiJobBuild thisBuild, AbstractBuild jobBuild) {
		// Env variables map
		Map<String, String> variables = new HashMap<String, String>();
		
		String jobName = jobBuild.getProject().getName();
		String jobNameSafe = jobName.replaceAll("[^A-Za-z0-9]", "_").toUpperCase();
		String buildNumber = Integer.toString(jobBuild.getNumber());		
		String buildResult = jobBuild.getResult().toString();
		
		// These will always reference the last build
		variables.put("LAST_TRIGGERED_JOB_NAME", jobName);
		variables.put("TRIGGERED_BUILD_NUMBER_" + jobNameSafe, buildNumber);
		variables.put("TRIGGERED_BUILD_RESULT_" + jobNameSafe, buildResult);

		// Per-Build Variables
		variables.put("TRIGGERED_BUILD_RESULT_" + jobNameSafe + "RUN" + buildNumber, buildResult);

		if (variables.get("TRIGGERED_JOB_NAMES") == null) {
			variables.put("TRIGGERED_JOB_NAMES", jobName);
		} else {
			String triggeredJobNames = variables.get("TRIGGERED_JOB_NAMES") + "," + jobName;
			variables.put("TRIGGERED_JOB_NAMES", triggeredJobNames);
		}

		if (variables.get("TRIGGERED_BUILD_NUMBERS_" + jobNameSafe) == null) {
			variables.put("TRIGGERED_BUILD_NUMBERS_" + jobNameSafe, buildNumber);
		} else {
			String triggeredBuildNumbers = variables.get("TRIGGERED_BUILD_NUMBERS_" + jobNameSafe) + "," + buildNumber;
			variables.put("TRIGGERED_BUILD_NUMBERS_" + jobNameSafe, triggeredBuildNumbers);
		}

		if (variables.get("TRIGGERED_BUILD_RUN_COUNT_" + jobNameSafe) == null) {
			variables.put("TRIGGERED_BUILD_RUN_COUNT_" + jobNameSafe, "1");
		} else {
			String runCount = Integer.toString(Integer.parseInt(variables.get("TRIGGERED_BUILD_RUN_COUNT_" + jobNameSafe)) + 1);
			variables.put("TRIGGERED_BUILD_RUN_COUNT_" + jobNameSafe, runCount);
		}


		//Set the new build variables map
		thisBuild.addAction(new EnvInjectBuilderContributionAction(variables));
	}

	@SuppressWarnings("rawtypes")
	private void prepareActions(AbstractBuild build, AbstractProject project,
			PhaseJobsConfig projectConfig, BuildListener listener,
			List<Action> actions) throws IOException, InterruptedException {
		List<Action> parametersActions = null;
		// if (projectConfig.hasProperties()) {
		parametersActions = (List<Action>) projectConfig.getActions(build,
				listener, project, projectConfig.isCurrParams());
		actions.addAll(parametersActions);
		// }

		ParametersAction currParametersAction = null;
	}

	public String getPhaseName() {
		return phaseName;
	}

	public void setPhaseName(String phaseName) {
		this.phaseName = phaseName;
	}

	public List<PhaseJobsConfig> getPhaseJobs() {
		return phaseJobs;
	}

	public void setPhaseJobs(List<PhaseJobsConfig> phaseJobs) {
		this.phaseJobs = phaseJobs;
	}

	public boolean phaseNameExist(String phaseName) {
		for (PhaseJobsConfig phaseJob : phaseJobs) {
			if (phaseJob.getDisplayName().equals(phaseName)) {
				return true;
			}
		}
		return false;
	}

	private final static class AbstractProjectKey {

		private AbstractProject project;

		AbstractProjectKey(AbstractProject project) {
			this.project = project;
		}

		public AbstractProject getProject() {
			return project;
		}
	}

	@Extension
	public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return jobType.equals(MultiJobProject.class);
		}

		@Override
		public String getDisplayName() {
			return "MultiJob Phase";
		}

		@Override
		public Builder newInstance(StaplerRequest req, JSONObject formData)
				throws FormException {
			return req.bindJSON(MultiJobBuilder.class, formData);
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) {
			save();
			return true;
		}
	}

	@SuppressWarnings("rawtypes")
	public void buildDependencyGraph(AbstractProject owner,
			DependencyGraph graph) {
		Hudson hudson = Hudson.getInstance();
		List<PhaseJobsConfig> phaseJobsConfigs = getPhaseJobs();

		if (phaseJobsConfigs == null)
			return;
		for (PhaseJobsConfig project : phaseJobsConfigs) {
			TopLevelItem topLevelItem = hudson.getItem(project.getJobName());
			if (topLevelItem instanceof AbstractProject) {
				Dependency dependency = new Dependency(owner,
						(AbstractProject) topLevelItem) {

					@Override
					public boolean shouldTriggerBuild(AbstractBuild build,
							TaskListener listener, List<Action> actions) {
						return false;
					}

				};
				graph.addDependency(dependency);
			}
		}
	}

	public boolean onJobRenamed(String oldName, String newName) {
		boolean changed = false;
		for (Iterator i = phaseJobs.iterator(); i.hasNext();) {
			PhaseJobsConfig phaseJobs = (PhaseJobsConfig) i.next();
			String jobName = phaseJobs.getJobName();
			if (jobName.trim().equals(oldName)) {
				if (newName != null) {
					phaseJobs.setJobName(newName);
					changed = true;
				} else {
					i.remove();
					changed = true;
				}
			}
		}
		return changed;
	}

	public boolean onJobDeleted(String oldName) {
		return onJobRenamed(oldName, null);
	}

	public static enum ContinuationCondition {

		SUCCESSFUL("Successful") {
			@Override
			public boolean isContinue(AbstractBuild build) {
				return build.getResult().equals(Result.SUCCESS);
			}
		},
		UNSTABLE("Stable or Unstable but not Failed") {
			@Override
			public boolean isContinue(AbstractBuild build) {
				return build.getResult().isBetterOrEqualTo(Result.UNSTABLE);
			}
		},
		COMPLETED("Complete (always continue)") {
			@Override
			public boolean isContinue(AbstractBuild build) {
				return build.getResult().equals(Result.ABORTED) ? true : 
					build.getResult().isBetterOrEqualTo(Result.FAILURE);
			}
		};

		abstract public boolean isContinue(AbstractBuild build);

		private ContinuationCondition(String label) {
			this.label = label;
		}

		final private String label;

		public String getLabel() {
			return label;
		}
	}

	public ContinuationCondition getContinuationCondition() {
		return continuationCondition;
	}

	public void setContinuationCondition(
			ContinuationCondition continuationCondition) {
		this.continuationCondition = continuationCondition;
	}
}
