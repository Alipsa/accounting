<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>${title}</h2>
  <div class="metrics">
    <#list summaryLines as line>
      <div class="metric">
        <span class="metric-label">${line?keep_before(':')}</span>
        <span class="metric-value">${line?keep_after(': ')}</span>
      </div>
    </#list>
  </div>
  <table>
    <thead>
      <tr>
        <#list tableHeaders as header>
          <th>${header}</th>
        </#list>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <tr>
          <#list row as cell>
            <td>${cell}</td>
          </#list>
        </tr>
      </#list>
    </tbody>
  </table>
</@layout.page>
