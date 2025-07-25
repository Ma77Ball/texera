/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import { ComponentFixture, TestBed, waitForAsync } from "@angular/core/testing";
import { RouterTestingModule } from "@angular/router/testing";
import { HttpClientTestingModule } from "@angular/common/http/testing";
import { FormsModule, ReactiveFormsModule } from "@angular/forms";
import { UserWorkflowComponent } from "./user-workflow.component";
import { WorkflowPersistService } from "../../../../common/service/workflow-persist/workflow-persist.service";
import { StubWorkflowPersistService } from "../../../../common/service/workflow-persist/stub-workflow-persist.service";
import { ShareAccessComponent } from "../share-access/share-access.component";
import { HttpClient } from "@angular/common/http";
import { ShareAccessService } from "../../../service/user/share-access/share-access.service";
import { UserService } from "../../../../common/service/user/user.service";
import { StubUserService } from "../../../../common/service/user/stub-user.service";
import { NzDropDownModule } from "ng-zorro-antd/dropdown";
import { NzCardModule } from "ng-zorro-antd/card";
import { NzListModule } from "ng-zorro-antd/list";
import { NzCalendarModule } from "ng-zorro-antd/calendar";
import { NzSelectModule } from "ng-zorro-antd/select";
import { NzPopoverModule } from "ng-zorro-antd/popover";
import { NzDatePickerModule } from "ng-zorro-antd/date-picker";
import { en_US, NZ_I18N } from "ng-zorro-antd/i18n";
import { NoopAnimationsModule } from "@angular/platform-browser/animations";
import { OperatorMetadataService } from "../../../../workspace/service/operator-metadata/operator-metadata.service";
import { StubOperatorMetadataService } from "../../../../workspace/service/operator-metadata/stub-operator-metadata.service";
import { NzUploadModule } from "ng-zorro-antd/upload";
import { ScrollingModule } from "@angular/cdk/scrolling";
import { NzAvatarModule } from "ng-zorro-antd/avatar";
import { NzToolTipModule } from "ng-zorro-antd/tooltip";
import {
  testWorkflowEntries,
  testWorkflowFileNameConflictEntries,
  mockUserInfo,
} from "../../user-dashboard-test-fixtures";
import { FiltersComponent } from "../filters/filters.component";
import { UserWorkflowListItemComponent } from "./user-workflow-list-item/user-workflow-list-item.component";
import { UserProjectService } from "../../../service/user/project/user-project.service";
import { StubUserProjectService } from "../../../service/user/project/stub-user-project.service";
import { SearchService } from "../../../service/user/search.service";
import { StubSearchService } from "../../../service/user/stub-search.service";
import { SearchResultsComponent } from "../search-results/search-results.component";
import { delay } from "rxjs";
import { NzModalService } from "ng-zorro-antd/modal";
import { NzButtonModule } from "ng-zorro-antd/button";
import { DownloadService } from "../../../service/user/download/download.service";
import { of } from "rxjs";
import { commonTestProviders } from "../../../../common/testing/test-utils";

describe("SavedWorkflowSectionComponent", () => {
  let component: UserWorkflowComponent;
  let fixture: ComponentFixture<UserWorkflowComponent>;

  let downloadServiceSpy: jasmine.SpyObj<DownloadService>;

  beforeEach(waitForAsync(() => {
    downloadServiceSpy = jasmine.createSpyObj<DownloadService>(["downloadWorkflowsAsZip"]);

    TestBed.configureTestingModule({
      declarations: [
        UserWorkflowComponent,
        ShareAccessComponent,
        FiltersComponent,
        UserWorkflowListItemComponent,
        SearchResultsComponent,
      ],
      providers: [
        NzModalService,
        { provide: WorkflowPersistService, useValue: new StubWorkflowPersistService(testWorkflowEntries) },
        { provide: UserProjectService, useValue: new StubUserProjectService() },
        HttpClient,
        ShareAccessService,
        { provide: OperatorMetadataService, useClass: StubOperatorMetadataService },
        { provide: NZ_I18N, useValue: en_US },
        { provide: UserService, useClass: StubUserService },
        {
          provide: SearchService,
          useValue: new StubSearchService(testWorkflowEntries, mockUserInfo),
        },
        { provide: DownloadService, useValue: downloadServiceSpy },
        ...commonTestProviders,
      ],
      imports: [
        FormsModule,
        RouterTestingModule,
        HttpClientTestingModule,
        ReactiveFormsModule,
        NzDropDownModule,
        NzCardModule,
        NzListModule,
        NzCalendarModule,
        NzDatePickerModule,
        NzSelectModule,
        NzPopoverModule,
        NzAvatarModule,
        NzToolTipModule,
        NzUploadModule,
        ScrollingModule,
        NoopAnimationsModule,
        NzButtonModule,
      ],
    }).compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(UserWorkflowComponent);
    component = fixture.componentInstance;
    component.filters = TestBed.createComponent(FiltersComponent).componentInstance;
    component.filters.masterFilterList = [];
    component.filters.selectedMtime = [];
    component.filters.selectedMtime = [];
    component.searchResultsComponent = TestBed.createComponent(SearchResultsComponent).componentInstance;
    fixture.detectChanges();
  });

  // TODO: add this test case back and figure out why it failed
  // xit("Modal Opened, then Closed", () => {
  //   const modalRef: NgbModalRef = modalService.open(NgbdModalWorkflowShareAccessComponent);
  //   spyOn(modalService, "open").and.returnValue(modalRef);
  //   component.onClickOpenShareAccess(testWorkflowEntries[0]);
  //   expect(modalService.open).toHaveBeenCalled();
  //   fixture.detectChanges();
  //   modalRef.dismiss();
  // });
  const waitForLoading = async () => {
    while (component.searchResultsComponent.loading) {
      await delay(10);
    }
  };

  it("searchNoInput", async () => {
    // When no search input is provided, it should show all workflows.
    await component.search();
    expect(component.searchResultsComponent.loading).toBeFalse();
    const SortedCase = component.searchResultsComponent.entries.map(workflow => workflow.name);
    expect(SortedCase).toEqual(["workflow 1", "workflow 2", "workflow 3", "workflow 4", "workflow 5"]);
    console.log("Master Filter List:", component.filters.masterFilterList);

    expect(component.filters.masterFilterList).toEqual([]);
  });

  it("searchByWorkflowName", async () => {
    // If the name "workflow 5" is entered as a single phrase, only workflow 5 should be returned, rather
    // than all containing the keyword "workflow".
    component.filters.masterFilterList = ["workflow 5"];
    await waitForLoading();
    expect(component.searchResultsComponent.loading).toBeFalse();
    const SortedCase = component.searchResultsComponent.entries.map(workflow => workflow.name);
    expect(SortedCase).toEqual(["workflow 5"]);
    expect(component.filters.masterFilterList).toEqual(["workflow 5"]);
  });

  it("searchByOwners", async () => {
    // If the owner filter is applied, only those workflow ownered by that user should be returned.
    component.filters.owners[0].checked = true;
    component.filters.updateSelectedOwners();
    await waitForLoading();
    expect(component.searchResultsComponent.loading).toBeFalse();
    const SortedCase = component.searchResultsComponent.entries.map(workflow => workflow.name);
    expect(SortedCase).toEqual(["workflow 1", "workflow 2"]);
    expect(component.filters.masterFilterList).toEqual(["owner: Texera"]);
  });

  it("searchByIDs", async () => {
    // If the ID filter is applied, only those workflows should be returned.
    component.filters.wids[0].checked = true;
    component.filters.wids[1].checked = true;
    component.filters.wids[2].checked = true;
    component.filters.updateSelectedIDs();
    await waitForLoading();
    expect(component.searchResultsComponent.loading).toBeFalse();
    const SortedCase = component.searchResultsComponent.entries.map(workflow => workflow.name);
    expect(SortedCase).toEqual(["workflow 1", "workflow 2", "workflow 3"]);
    expect(component.filters.masterFilterList).toEqual(["id: 1", "id: 2", "id: 3"]);
  });

  it("searchByProjects", async () => {
    component.filters.userProjectsDropdown = [
      { pid: 1, name: "Project1", checked: false },
      { pid: 2, name: "Project2", checked: false },
      { pid: 3, name: "Project3", checked: false },
    ];

    // If the project filter is applied, only those workflows belonging to those projects should be returned.
    component.filters.userProjectsDropdown[0].checked = true;
    component.filters.updateSelectedProjects();
    await waitForLoading();
    expect(component.searchResultsComponent.loading).toBeFalse();
    const SortedCase = component.searchResultsComponent.entries.map(workflow => workflow.name);
    expect(SortedCase).toEqual(["workflow 1", "workflow 2", "workflow 3"]);
    expect(component.filters.masterFilterList).toEqual(["project: Project1"]);
  });

  it("searchByCreationTime", async () => {
    // If the creation time filter is applied, only those workflows matching the date range should be returned.
    component.filters.selectedCtime = [new Date(1970, 0, 3), new Date(1981, 2, 13)];
    component.filters.buildMasterFilterList();
    await waitForLoading();
    expect(component.searchResultsComponent.loading).toBeFalse();
    const SortedCase = component.searchResultsComponent.entries.map(workflow => workflow.name);
    expect(SortedCase).toEqual(["workflow 4", "workflow 5"]);
    expect(component.filters.masterFilterList).toEqual(["ctime: 1970-01-03 ~ 1981-03-13"]);
  });

  it("searchByModifyTime", async () => {
    // If the modified time filter is applied, only those workflows matching the date range should be returned.
    component.filters.selectedMtime = [new Date(1970, 0, 3), new Date(1981, 2, 13)];
    component.filters.buildMasterFilterList();
    await waitForLoading();
    expect(component.searchResultsComponent.loading).toBeFalse();
    const SortedCase = component.searchResultsComponent.entries.map(workflow => workflow.name);
    expect(SortedCase).toEqual(["workflow 4", "workflow 5"]);
    expect(component.filters.masterFilterList).toEqual(["mtime: 1970-01-03 ~ 1981-03-13"]);
  });

  /*
   * To add operators to this test:
   *   1. Check if the operator's group is true
   *   2. Mark the selected operator "checked" as true
   *   3. Push the operator's operatorType to operatorSelectionList
   *   4. Update masterFilterList to have the correct tags
   *
   *   - Recommendation: print out the component.operators after the operatorDropdownRequest is made
   *
   *   - See searchByManyOperators test
   */
  it("searchByOperators", async () => {
    // If a single operator filter is provided, only the workflows containing that operator should be returned.
    const operatorGroup = component.filters.operators.get("Analysis");
    if (operatorGroup) {
      operatorGroup[2].checked = true; // sentiment analysis
      component.filters.updateSelectedOperators();
    }
    await waitForLoading();
    const SortedCase = component.searchResultsComponent.entries.map(workflow => workflow.name);
    expect(SortedCase).toEqual(["workflow 1", "workflow 2", "workflow 3"]);
    expect(component.filters.masterFilterList).toEqual(["operator: Sentiment Analysis"]); // userFriendlyName
  });

  it("searchByManyOperators", async () => {
    // If a multiple operator filters are provided, workflows containing any of the provided operators should be returned.
    const operatorGroup = component.filters.operators.get("Analysis");
    const operatorGroup2 = component.filters.operators.get("View Results");
    if (operatorGroup && operatorGroup2) {
      operatorGroup[2].checked = true; // sentiment analysis
      operatorGroup2[0].checked = true;
      component.filters.updateSelectedOperators();
    }
    await waitForLoading();
    const SortedCase = component.searchResultsComponent.entries.map(workflow => workflow.name);
    expect(SortedCase).toEqual(["workflow 1", "workflow 2", "workflow 3"]);
    expect(component.filters.masterFilterList).toEqual(["operator: Sentiment Analysis", "operator: View Results"]); // userFriendlyName
  });

  it("searchByManyParameters", async () => {
    // Apply the project, ID, owner, and operator filter all at once.
    component.filters.masterFilterList = ["1"];
    const operatorGroup = component.filters.operators.get("Analysis");
    if (operatorGroup) {
      operatorGroup[3].checked = true; // Aggregation operator
      component.filters.updateSelectedOperators();
      component.filters.userProjectsDropdown = [
        { pid: 1, name: "Project1", checked: false },
        { pid: 2, name: "Project2", checked: false },
        { pid: 3, name: "Project3", checked: false },
      ];

      component.filters.owners[0].checked = true; //Texera
      component.filters.owners[1].checked = true; //Angular
      component.filters.wids[0].checked = true;
      component.filters.wids[1].checked = true;
      component.filters.wids[2].checked = true; //id 1,2,3
      component.filters.userProjectsDropdown[0].checked = true; //Project 1
      component.filters.selectedCtime = [new Date(1970, 0, 1), new Date(1973, 2, 11)];
      component.filters.selectedMtime = [new Date(1970, 0, 1), new Date(1982, 3, 14)];
      //add/select new search parameter here

      component.filters.updateSelectedProjects();
      component.filters.updateSelectedIDs();
      component.filters.updateSelectedOwners();
    }
    await waitForLoading();
    await component.search();
    const SortedCase = component.searchResultsComponent.entries.map(workflow => workflow.name);
    expect(SortedCase).toEqual(["workflow 1"]);
    expect(component.filters.masterFilterList).toEqual(
      jasmine.arrayWithExactContents([
        "1",
        "owner: Texera",
        "owner: Angular",
        "id: 1",
        "id: 2",
        "id: 3",
        "operator: Aggregation",
        "project: Project1",
        "ctime: 1970-01-01 ~ 1973-03-11",
        "mtime: 1970-01-01 ~ 1982-04-14",
      ])
    );
  });

  it("downloads checked files", async () => {
    // If multiple workflows in a single batch download have name conflicts, rename them as workflow-1, workflow-2, etc.
    component.searchResultsComponent.entries = component.searchResultsComponent.entries.concat(
      testWorkflowFileNameConflictEntries
    );
    testWorkflowFileNameConflictEntries[0].checked = true;
    testWorkflowFileNameConflictEntries[2].checked = true;

    downloadServiceSpy.downloadWorkflowsAsZip.and.returnValue(of(new Blob()));

    await component.onClickOpenDownloadZip();

    expect(downloadServiceSpy.downloadWorkflowsAsZip).toHaveBeenCalledTimes(1);
    expect(downloadServiceSpy.downloadWorkflowsAsZip).toHaveBeenCalledWith([
      {
        id: testWorkflowFileNameConflictEntries[0].workflow.workflow.wid!,
        name: testWorkflowFileNameConflictEntries[0].workflow.workflow.name,
      },
      {
        id: testWorkflowFileNameConflictEntries[2].workflow.workflow.wid!,
        name: testWorkflowFileNameConflictEntries[2].workflow.workflow.name,
      },
    ]);

    // Check that the checked entries are unchecked after download
    expect(testWorkflowFileNameConflictEntries[0].checked).toBeTrue();
    expect(testWorkflowFileNameConflictEntries[2].checked).toBeTrue();
  });
});
