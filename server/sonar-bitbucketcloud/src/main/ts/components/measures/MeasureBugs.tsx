/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
import * as React from 'react';
import BugIcon from '@sqcore/components/icons-components/BugIcon';
import Measure from '@sqcore/components/measure/Measure';
import Rating from '@sqcore/components/ui/Rating';
import { ProjectData } from '../../types';

interface Props extends Pick<ProjectData, 'measures'> {}

export default function MeasureBugs({ measures }: Props) {
  return (
    <div className="project-card-measure">
      <div className="project-card-measure-value">
        <Measure
          className="little-spacer-right"
          metricKey="bugs"
          metricType="SHORT_INT"
          value={measures['bugs']}
        />
        <Rating value={measures['reliability_rating']} />
      </div>
      <div className="project-card-measure-title">
        <BugIcon />
        Bugs
      </div>
    </div>
  );
}
