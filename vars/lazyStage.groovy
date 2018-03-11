#!groovy

/*
 * This work is protected under copyright law in the Kingdom of
 * The Netherlands. The rules of the Berne Convention for the
 * Protection of Literary and Artistic Works apply.
 * Digital Me B.V. is the copyright owner.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

def call (body) {
	def params = [
		name:		null,
		tasks:		[],
		dockerArgs:	'',
	]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = params
	body()

	// Retrieving global config
	def config = lazyConfig()

	// Check parameters and config for possible error
	def err = null
	if (!params.name) {
		err = 'Stage always needs a name'
	} else if (!params.tasks) {
		err = 'Stage always needs some tasks'
	}
	if (err) {
		error err
	}

	// Skip stage if not listed in the config
	if (!config.stages.contains(params.name)) {
		echo "Stage ${params.name} will be skipped (config.stages.${params.name} is not set)"
		return 0
	}

	stage(Character.toUpperCase(params.name.charAt(0)).toString() + params.name.substring(1)) {
		if (config.verbose) echo "Stage ${params.name} for ${config.name} begins here"

		// Collect all tasks in a Map of pipeline branches (as Closure) to be run in parallel
		def branches = [:]
		def index = 1

		params.tasks.each { task ->
			// Lookup node label to be used for this task bloc
			def label = config.labels.default
			if (task.on) {
				label = config.labels[task.on]
			} else if (task.inside) {
				label = config.labels.docker
			}

			// Prepare List of dists to be used for this task block
			def dists = []
			if (task.inside == '*') {
				dists = config.dists
			} else if (task.inside) {
				dists = task.inside
			}

			if (dists) {
				// If inside docker, keeps adding each dist as a new block
				dists.each { dist ->
					if (config.verbose) echo "Stage ${params.name} inside ${dist} will be done using Docker (label = ${config.labels.docker})"
					def branch = "${params.name}_${index++}_${dist}"
					branches += [
						(branch): {
							node(label: config.labels.docker) {
								checkout scm
								try {
									lazyDocker(params.name, task, dist, params.dockerArgs)
								} catch (e) {
									error e.toString()
								} finally {
									step([$class: 'WsCleanup'])
								}
							}
						}
					]
				}
			} else {
				// If not, just add the block
				def branch = "${params.name}_${index++}_${task.on}"
				branches += [
					(branch): {
						node(label: config.labels.default) {
							checkout scm
							try {
								// Execute each steps
								lazyStep(params.name, task.exec, task.on).each { step ->
									step ()
								}
							} catch (e) {
								error e.toString()
							} finally {
								step([$class: 'WsCleanup'])
							}
						}
					}
				]
			}
		}

		// Now we can execute block(s) in parallel
		parallel(branches)
		
		if (config.verbose) echo "Stage ${params.name} for ${config.name} ends here"
	}
}
